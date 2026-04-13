"""
TRACE Shield API — FastAPI Backend
Endpoints: /api/verify, /api/check, /api/certificate, /api/badge, /api/creators
Patent Pending — George Mundin / BITS
"""
import os
import json
import uuid
import sqlite3
from datetime import datetime, timezone
from typing import Optional

from fastapi import FastAPI, UploadFile, File, HTTPException, Header, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from hash_engine import sha256_hash, perceptual_hash, perceptual_distance, similarity_verdict
from solana_anchor import anchor_to_solana
from cert_generator import build_certificate
from database import get_conn
from stripe_billing import get_plan_info, verifications_remaining, create_checkout_session
from admin_routes import router as admin_router

# ── App ──────────────────────────────────────────────────────────────────────
app = FastAPI(
    title="TRACE Shield API",
    description="Blockchain-backed content authentication for creators. Patent Pending — BITS.",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

CERT_DIR = os.path.join(os.path.dirname(__file__), "../certs")
STATIC_DIR = os.path.join(os.path.dirname(__file__), "../static")
SHIELD_DIR = os.path.join(os.path.dirname(__file__), "../shield")
CHECK_DIR = os.path.join(os.path.dirname(__file__), "../check")

os.makedirs(CERT_DIR, exist_ok=True)
os.makedirs(STATIC_DIR, exist_ok=True)

app.include_router(admin_router)


# ── Models ───────────────────────────────────────────────────────────────────
class CreatorSignup(BaseModel):
    email: str
    name: str
    plan: str = "shield"


# ── Auth helper (MVP: simple API key) ────────────────────────────────────────
def get_creator(x_creator_id: str = Header(...)):
    conn = get_conn()
    row = conn.execute("SELECT * FROM creators WHERE id=? AND active=1", (x_creator_id,)).fetchone()
    conn.close()
    if not row:
        raise HTTPException(status_code=401, detail="Invalid or inactive creator ID")
    return dict(row)


# ── Health ────────────────────────────────────────────────────────────────────
@app.get("/api/health")
def health():
    return {"status": "ok", "product": "TRACE Shield", "version": "1.0.0"}


# ── Creator Signup ────────────────────────────────────────────────────────────
@app.post("/api/creators/signup")
def signup(body: CreatorSignup):
    creator_id = "CR-" + str(uuid.uuid4()).upper()[:12]
    conn = get_conn()
    try:
        conn.execute(
            "INSERT INTO creators (id, email, name, plan) VALUES (?,?,?,?)",
            (creator_id, body.email.lower().strip(), body.name.strip(), body.plan)
        )
        conn.commit()
    except sqlite3.IntegrityError:
        conn.close()
        raise HTTPException(status_code=409, detail="Email already registered")
    conn.close()
    return {
        "creator_id": creator_id,
        "email": body.email,
        "name": body.name,
        "plan": body.plan,
        "message": "Account created. Use your creator_id as X-Creator-Id header for authenticated requests."
    }


# ── /api/verify — Core endpoint: hash + anchor + certify ─────────────────────
@app.post("/api/verify")
async def verify(
    file: UploadFile = File(...),
    creator: dict = Depends(get_creator),
):
    """
    Upload a video/image. TRACE will:
    1. Generate SHA-256 + perceptual hash
    2. Anchor to Solana blockchain
    3. Return a TRACE Certificate
    """
    data = await file.read()
    file_size = len(data)

    if file_size == 0:
        raise HTTPException(status_code=400, detail="Empty file")
    if file_size > 500 * 1024 * 1024:  # 500MB limit
        raise HTTPException(status_code=400, detail="File too large (500MB max)")

    # 1. Hash
    sha256 = sha256_hash(data)
    phash = perceptual_hash(data, file.content_type or "")

    # 2. Check for duplicate (same creator, same content)
    conn = get_conn()
    existing = conn.execute(
        "SELECT cert_id, created_at FROM certificates WHERE creator_id=? AND sha256_hash=?",
        (creator["id"], sha256)
    ).fetchone()
    if existing:
        conn.close()
        return {
            "status": "already_verified",
            "cert_id": existing["cert_id"],
            "message": "This exact file was already authenticated.",
            "original_cert_date": existing["created_at"],
        }

    # 3. Anchor to Solana
    cert_data = build_certificate(
        creator_name=creator["name"],
        creator_email=creator["email"],
        creator_id=creator["id"],
        filename=file.filename or "unknown",
        sha256_hash=sha256,
        perceptual_hash=phash,
        file_size_bytes=file_size,
        solana_result={"network": "devnet", "anchored": False, "tx": None},  # placeholder for anchor call
    )
    solana_result = anchor_to_solana(cert_data["cert_id"], sha256, phash)

    # Update cert with Solana result
    cert_data["blockchain"] = {
        "network": solana_result.get("network", "devnet"),
        "tx_id": solana_result.get("tx"),
        "anchored": solana_result.get("anchored", False),
        "explorer_url": solana_result.get("explorer"),
        "simulated": solana_result.get("simulated", True),
    }

    # Update cert JSON on disk
    cert_path = os.path.join(CERT_DIR, f"{cert_data['cert_id']}.json")
    with open(cert_path, "w") as f:
        json.dump(cert_data, f, indent=2)

    # 4. Store in DB
    conn.execute(
        """INSERT INTO certificates
           (cert_id, creator_id, filename, sha256_hash, perceptual_hash, file_size_bytes, solana_tx, cert_url)
           VALUES (?,?,?,?,?,?,?,?)""",
        (
            cert_data["cert_id"],
            creator["id"],
            file.filename or "unknown",
            sha256,
            phash,
            file_size,
            solana_result.get("tx"),
            cert_data["verification"]["url"],
        )
    )
    conn.execute(
        "UPDATE creators SET verifications_used = verifications_used + 1 WHERE id=?",
        (creator["id"],)
    )
    conn.commit()
    conn.close()

    return {
        "status": "verified",
        "cert_id": cert_data["cert_id"],
        "sha256_hash": sha256,
        "perceptual_hash": phash,
        "blockchain": cert_data["blockchain"],
        "certificate_url": cert_data["verification"]["url"],
        "qr_code_url": cert_data["verification"]["qr_code_url"],
        "issued_utc": cert_data["timestamps"]["issued_utc"],
    }


# ── /api/check — Public verification (no auth required) ──────────────────────
@app.post("/api/check")
async def check(file: UploadFile = File(...)):
    """
    Anyone can submit a file to check if it's in the TRACE database.
    Returns: verified / modified / not_in_trace
    """
    data = await file.read()
    sha256 = sha256_hash(data)
    phash = perceptual_hash(data, file.content_type or "")

    conn = get_conn()

    # Exact match
    exact = conn.execute(
        """SELECT c.cert_id, c.filename, c.created_at, c.perceptual_hash,
                  cr.name as creator_name, c.solana_tx
           FROM certificates c JOIN creators cr ON c.creator_id = cr.id
           WHERE c.sha256_hash=?""",
        (sha256,)
    ).fetchone()

    if exact:
        conn.close()
        return {
            "result": "VERIFIED",
            "verdict": "✅ This content is TRACE Verified and authentic.",
            "cert_id": exact["cert_id"],
            "creator": exact["creator_name"],
            "filename": exact["filename"],
            "authenticated_on": exact["created_at"],
            "blockchain_tx": exact["solana_tx"],
        }

    # Fuzzy match (perceptual)
    if phash:
        all_certs = conn.execute(
            """SELECT c.cert_id, c.filename, c.created_at, c.perceptual_hash,
                      cr.name as creator_name, c.solana_tx
               FROM certificates c JOIN creators cr ON c.creator_id = cr.id
               WHERE c.perceptual_hash IS NOT NULL"""
        ).fetchall()

        for row in all_certs:
            dist = perceptual_distance(phash, row["perceptual_hash"])
            verdict_str = similarity_verdict(dist)
            if verdict_str in ("EXACT_MATCH", "HIGHLY_SIMILAR"):
                conn.close()
                return {
                    "result": "MODIFIED",
                    "verdict": "⚠️ This appears to be a modified version of TRACE-authenticated content.",
                    "cert_id": row["cert_id"],
                    "original_creator": row["creator_name"],
                    "original_filename": row["filename"],
                    "original_authenticated_on": row["created_at"],
                    "similarity": verdict_str,
                    "perceptual_distance": dist,
                }

    conn.close()
    return {
        "result": "NOT_IN_TRACE",
        "verdict": "❓ This content has not been authenticated through TRACE.",
        "sha256_hash": sha256,
        "suggestion": "If you created this content, sign up at shield.bitstrace.com to authenticate it.",
    }


# ── /api/certificate/<id> — Get certificate details ──────────────────────────
@app.get("/api/certificate/{cert_id}")
def get_certificate(cert_id: str):
    cert_path = os.path.join(CERT_DIR, f"{cert_id}.json")
    if not os.path.exists(cert_path):
        raise HTTPException(status_code=404, detail="Certificate not found")
    with open(cert_path) as f:
        return json.load(f)


# ── /api/certificate/<id>/qr — QR code image ─────────────────────────────────
@app.get("/api/certificate/{cert_id}/qr")
def get_qr(cert_id: str):
    qr_path = os.path.join(CERT_DIR, f"{cert_id}_qr.png")
    if not os.path.exists(qr_path):
        raise HTTPException(status_code=404, detail="QR code not found")
    return FileResponse(qr_path, media_type="image/png")


# ── /api/badge/<id> — Embeddable verification badge ──────────────────────────
@app.get("/api/badge/{cert_id}", response_class=HTMLResponse)
def get_badge(cert_id: str):
    cert_path = os.path.join(CERT_DIR, f"{cert_id}.json")
    if not os.path.exists(cert_path):
        raise HTTPException(status_code=404, detail="Certificate not found")
    with open(cert_path) as f:
        cert = json.load(f)

    verify_url = cert["verification"]["url"]
    issued = cert["timestamps"]["issued_utc"][:10]

    badge_html = f"""<!DOCTYPE html>
<html>
<head>
<style>
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  body {{ background: transparent; font-family: system-ui, sans-serif; }}
  .badge {{
    display: inline-flex; align-items: center; gap: 8px;
    background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
    border: 1px solid #dc2626;
    border-radius: 6px; padding: 6px 12px;
    text-decoration: none; color: white;
  }}
  .shield {{ color: #dc2626; font-size: 14px; }}
  .text {{ display: flex; flex-direction: column; }}
  .title {{ font-size: 11px; font-weight: 700; letter-spacing: 1px; color: #dc2626; }}
  .sub {{ font-size: 10px; color: #94a3b8; }}
</style>
</head>
<body>
<a href="{verify_url}" target="_blank" class="badge">
  <span class="shield">🛡</span>
  <span class="text">
    <span class="title">TRACE VERIFIED</span>
    <span class="sub">Authenticated {issued}</span>
  </span>
</a>
</body>
</html>"""
    return HTMLResponse(badge_html)


# ── /api/creators/me — Creator dashboard data ────────────────────────────────
@app.get("/api/creators/me")
def get_me(creator: dict = Depends(get_creator)):
    conn = get_conn()
    certs = conn.execute(
        """SELECT cert_id, filename, sha256_hash, created_at, solana_tx, cert_url
           FROM certificates WHERE creator_id=? ORDER BY created_at DESC""",
        (creator["id"],)
    ).fetchall()
    conn.close()
    return {
        "creator": {
            "id": creator["id"],
            "name": creator["name"],
            "email": creator["email"],
            "plan": creator["plan"],
            "verifications_used": creator["verifications_used"],
            "member_since": creator["created_at"],
        },
        "certificates": [dict(c) for c in certs],
        "total_authenticated": len(certs),
    }


# ── /api/dispute — Compare two files ─────────────────────────────────────────
@app.post("/api/dispute")
async def dispute(
    original: UploadFile = File(...),
    suspect: UploadFile = File(...),
    creator: dict = Depends(get_creator),
):
    """
    Upload original + suspect file. TRACE compares them and returns a dispute report.
    Used when a creator thinks their content has been stolen/deepfaked.
    """
    orig_data = await original.read()
    susp_data = await suspect.read()

    orig_sha = sha256_hash(orig_data)
    susp_sha = sha256_hash(susp_data)
    orig_phash = perceptual_hash(orig_data, original.content_type or "")
    susp_phash = perceptual_hash(susp_data, suspect.content_type or "")

    # Check if original is in TRACE db
    conn = get_conn()
    orig_cert = conn.execute(
        "SELECT cert_id, created_at FROM certificates WHERE sha256_hash=? AND creator_id=?",
        (orig_sha, creator["id"])
    ).fetchone()
    conn.close()

    exact_match = orig_sha == susp_sha
    dist = perceptual_distance(orig_phash, susp_phash)
    verdict = similarity_verdict(dist)

    report = {
        "original_file": original.filename,
        "suspect_file": suspect.filename,
        "exact_match": exact_match,
        "perceptual_similarity": verdict,
        "perceptual_distance": dist,
        "original_in_trace": orig_cert is not None,
        "original_cert_id": orig_cert["cert_id"] if orig_cert else None,
        "original_cert_date": orig_cert["created_at"] if orig_cert else None,
        "hashes": {
            "original_sha256": orig_sha,
            "suspect_sha256": susp_sha,
            "original_phash": orig_phash,
            "suspect_phash": susp_phash,
        },
        "verdict": (
            "IDENTICAL — Files are byte-for-byte identical." if exact_match
            else f"MODIFIED — Content is {verdict.replace('_', ' ').lower()}. Likely deepfake or re-encoded copy." if verdict in ("HIGHLY_SIMILAR", "SIMILAR")
            else "DIFFERENT — Files do not appear to be related."
        ),
        "recommended_action": (
            "No action needed — files are identical." if exact_match
            else "File a DMCA takedown and attach your TRACE Certificate as blockchain proof." if verdict in ("HIGHLY_SIMILAR", "SIMILAR") and orig_cert
            else "Authenticate your original content via TRACE Shield to build a blockchain record first." if not orig_cert
            else "Files appear unrelated."
        ),
    }
    return report


# ── /api/creators/me/analytics ── Creator analytics ──────────────────────────
@app.get("/api/creators/me/analytics")
def get_analytics(creator: dict = Depends(get_creator)):
    conn = get_conn()
    # Certs per day (last 30 days)
    daily = conn.execute(
        """SELECT DATE(created_at) as day, COUNT(*) as count
           FROM certificates WHERE creator_id=?
           AND created_at > datetime('now', '-30 days')
           GROUP BY DATE(created_at) ORDER BY day ASC""",
        (creator["id"],)
    ).fetchall()

    # Total stats
    total = conn.execute("SELECT COUNT(*) FROM certificates WHERE creator_id=?", (creator["id"],)).fetchone()[0]
    anchored = conn.execute(
        "SELECT COUNT(*) FROM certificates WHERE creator_id=? AND solana_tx IS NOT NULL", (creator["id"],)
    ).fetchone()[0]

    plan_info = get_plan_info(creator["plan"])
    remaining = verifications_remaining(creator["plan"], creator["verifications_used"])

    conn.close()
    return {
        "total_authenticated": total,
        "anchored_on_chain": anchored,
        "verifications_used": creator["verifications_used"],
        "verifications_remaining": remaining,
        "plan": creator["plan"],
        "plan_display": plan_info["name"],
        "plan_price": plan_info["price_display"],
        "daily_activity": [dict(r) for r in daily],
    }


# ── /api/checkout — Stripe checkout ──────────────────────────────────────────
class CheckoutRequest(BaseModel):
    plan: str
    success_url: str = "https://shield.bitstrace.com/dashboard"
    cancel_url: str = "https://shield.bitstrace.com"

@app.post("/api/checkout")
def create_checkout(body: CheckoutRequest, creator: dict = Depends(get_creator)):
    result = create_checkout_session(
        creator_id=creator["id"],
        email=creator["email"],
        plan=body.plan,
        success_url=body.success_url,
        cancel_url=body.cancel_url,
    )
    return result


# ── /api/plans — List available plans ────────────────────────────────────────
@app.get("/api/plans")
def list_plans():
    from stripe_billing import PLAN_PRICES
    return {"plans": PLAN_PRICES}


# ── HTML page serving ─────────────────────────────────────────────────────────
def _serve_html(path: str):
    with open(path) as f:
        content = f.read()
    # Replace hardcoded localhost:8003 with dynamic API (same origin)
    content = content.replace("http://localhost:8003", "")
    content = content.replace("http://localhost:8002", "")
    return HTMLResponse(content)

@app.get("/", response_class=HTMLResponse)
def shield_home():
    return _serve_html(os.path.join(SHIELD_DIR, "index.html"))

@app.get("/check", response_class=HTMLResponse)
def check_home():
    return _serve_html(os.path.join(CHECK_DIR, "index.html"))

@app.get("/admin", response_class=HTMLResponse)
def admin_home():
    return _serve_html(os.path.join(os.path.dirname(__file__), "../admin/index.html"))

@app.get("/landing", response_class=HTMLResponse)
def landing_home():
    return _serve_html(os.path.join(os.path.dirname(__file__), "../landing/index.html"))

@app.get("/verify/{cert_id}", response_class=HTMLResponse)
def verify_page(cert_id: str):
    return _serve_html(os.path.join(CHECK_DIR, "index.html"))


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("TRACE_PORT", 8002))
    print(f"\n🛡  TRACE Shield API starting on port {port}")
    print(f"   Shield:  http://localhost:{port}/")
    print(f"   Check:   http://localhost:{port}/check")
    print(f"   Admin:   http://localhost:{port}/admin")
    print(f"   Docs:    http://localhost:{port}/docs\n")
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=True)
