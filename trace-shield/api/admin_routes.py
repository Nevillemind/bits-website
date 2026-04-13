"""
TRACE Shield — Admin API Routes
BITS internal management: creators, certificates, analytics, plan management.
"""
import os
from datetime import datetime, timezone
from fastapi import APIRouter, HTTPException, Header, Depends
from pydantic import BaseModel
from typing import Optional
from database import get_conn
from stripe_billing import PLAN_PRICES

router = APIRouter(prefix="/api/admin", tags=["admin"])

ADMIN_KEY = os.environ.get("TRACE_ADMIN_KEY", "bits-trace-admin-2026")


def require_admin(x_admin_key: str = Header(...)):
    if x_admin_key != ADMIN_KEY:
        raise HTTPException(status_code=403, detail="Invalid admin key")
    return True


# ── Stats Dashboard ──────────────────────────────────────────────────────────
@router.get("/stats")
def admin_stats(admin=Depends(require_admin)):
    conn = get_conn()
    total_creators = conn.execute("SELECT COUNT(*) FROM creators").fetchone()[0]
    active_creators = conn.execute("SELECT COUNT(*) FROM creators WHERE active=1").fetchone()[0]
    total_certs = conn.execute("SELECT COUNT(*) FROM certificates").fetchone()[0]
    total_checks = conn.execute("SELECT COUNT(*) FROM dispute_checks").fetchone()[0]
    anchored_certs = conn.execute("SELECT COUNT(*) FROM certificates WHERE solana_tx IS NOT NULL AND solana_tx NOT LIKE 'SIM_%'").fetchone()[0]

    # Plan breakdown
    plan_rows = conn.execute(
        "SELECT plan, COUNT(*) as cnt FROM creators WHERE active=1 GROUP BY plan"
    ).fetchall()
    plan_breakdown = {r["plan"]: r["cnt"] for r in plan_rows}

    # Recent signups (last 7 days)
    recent = conn.execute(
        "SELECT COUNT(*) FROM creators WHERE created_at > datetime('now', '-7 days')"
    ).fetchone()[0]

    # MRR estimate
    mrr = 0
    for plan, count in plan_breakdown.items():
        price_cents = PLAN_PRICES.get(plan, {}).get("price", 0)
        mrr += (price_cents / 100) * count

    # Recent verifications (last 7 days)
    recent_certs = conn.execute(
        "SELECT COUNT(*) FROM certificates WHERE created_at > datetime('now', '-7 days')"
    ).fetchone()[0]

    conn.close()
    return {
        "creators": {"total": total_creators, "active": active_creators, "new_last_7_days": recent},
        "certificates": {"total": total_certs, "anchored_on_chain": anchored_certs, "last_7_days": recent_certs},
        "checks": {"total": total_checks},
        "plan_breakdown": plan_breakdown,
        "mrr_estimate": round(mrr, 2),
        "arr_estimate": round(mrr * 12, 2),
    }


# ── List All Creators ─────────────────────────────────────────────────────────
@router.get("/creators")
def admin_list_creators(admin=Depends(require_admin)):
    conn = get_conn()
    rows = conn.execute(
        "SELECT *, (SELECT COUNT(*) FROM certificates WHERE creator_id=creators.id) as cert_count FROM creators ORDER BY created_at DESC"
    ).fetchall()
    conn.close()
    return {"creators": [dict(r) for r in rows]}


# ── Update Creator (plan, active, notes) ─────────────────────────────────────
class CreatorPatch(BaseModel):
    plan: Optional[str] = None
    active: Optional[int] = None
    notes: Optional[str] = None
    subscription_status: Optional[str] = None

@router.patch("/creators/{creator_id}")
def admin_update_creator(creator_id: str, body: CreatorPatch, admin=Depends(require_admin)):
    conn = get_conn()
    existing = conn.execute("SELECT * FROM creators WHERE id=?", (creator_id,)).fetchone()
    if not existing:
        conn.close()
        raise HTTPException(status_code=404, detail="Creator not found")

    updates = []
    params = []
    if body.plan is not None:
        updates.append("plan=?"); params.append(body.plan)
    if body.active is not None:
        updates.append("active=?"); params.append(body.active)
    if body.notes is not None:
        updates.append("notes=?"); params.append(body.notes)
    if body.subscription_status is not None:
        updates.append("subscription_status=?"); params.append(body.subscription_status)

    if updates:
        params.append(creator_id)
        conn.execute(f"UPDATE creators SET {', '.join(updates)} WHERE id=?", params)
        conn.commit()

    updated = conn.execute("SELECT * FROM creators WHERE id=?", (creator_id,)).fetchone()
    conn.close()
    return {"updated": True, "creator": dict(updated)}


# ── List All Certificates ─────────────────────────────────────────────────────
@router.get("/certificates")
def admin_list_certs(limit: int = 100, admin=Depends(require_admin)):
    conn = get_conn()
    rows = conn.execute(
        """SELECT c.*, cr.name as creator_name, cr.email as creator_email, cr.plan
           FROM certificates c JOIN creators cr ON c.creator_id = cr.id
           ORDER BY c.created_at DESC LIMIT ?""",
        (limit,)
    ).fetchall()
    conn.close()
    return {"certificates": [dict(r) for r in rows]}


# ── Verification Checks Log ───────────────────────────────────────────────────
@router.get("/checks")
def admin_list_checks(limit: int = 100, admin=Depends(require_admin)):
    conn = get_conn()
    rows = conn.execute(
        "SELECT * FROM dispute_checks ORDER BY created_at DESC LIMIT ?", (limit,)
    ).fetchall()
    conn.close()
    return {"checks": [dict(r) for r in rows]}


# ── Delete Creator (soft delete = deactivate) ─────────────────────────────────
@router.delete("/creators/{creator_id}")
def admin_deactivate_creator(creator_id: str, admin=Depends(require_admin)):
    conn = get_conn()
    conn.execute("UPDATE creators SET active=0 WHERE id=?", (creator_id,))
    conn.commit()
    conn.close()
    return {"deactivated": True, "creator_id": creator_id}
