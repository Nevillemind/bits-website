"""
TRACE Shield — Certificate Generator
Generates verifiable authenticity certificates with QR codes.
Patent Pending — George Mundin / BITS
"""
import os
import uuid
import json
from datetime import datetime, timezone
from typing import Optional
import qrcode
from PIL import Image, ImageDraw, ImageFont


CERT_DIR = os.path.join(os.path.dirname(__file__), "../certs")
BASE_URL = os.environ.get("TRACE_BASE_URL", "https://check.bitstrace.com")


def generate_cert_id() -> str:
    return "TRACE-" + str(uuid.uuid4()).upper().replace("-", "")[:16]


def generate_qr_code(cert_id: str) -> str:
    """Generate QR code pointing to the public verification URL."""
    os.makedirs(CERT_DIR, exist_ok=True)
    verify_url = f"{BASE_URL}/verify/{cert_id}"
    qr = qrcode.QRCode(version=1, error_correction=qrcode.constants.ERROR_CORRECT_H, box_size=6, border=2)
    qr.add_data(verify_url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#0f172a", back_color="white")
    qr_path = os.path.join(CERT_DIR, f"{cert_id}_qr.png")
    img.save(qr_path)
    return qr_path


def generate_certificate_json(
    cert_id: str,
    creator_name: str,
    creator_email: str,
    filename: str,
    sha256_hash: str,
    perceptual_hash: Optional[str],
    file_size_bytes: int,
    solana_result: dict,
) -> dict:
    """Generate the full certificate data structure."""
    now = datetime.now(timezone.utc)
    verify_url = f"{BASE_URL}/verify/{cert_id}"

    cert = {
        "cert_id": cert_id,
        "version": "1.0",
        "product": "TRACE Shield",
        "issuer": "Blockchain Integrative Technology Solutions (BITS)",
        "issuer_website": "https://bitstrace.com",
        "creator": {
            "name": creator_name,
            "email": creator_email,
        },
        "content": {
            "filename": filename,
            "file_size_bytes": file_size_bytes,
            "sha256_hash": sha256_hash,
            "perceptual_hash": perceptual_hash,
        },
        "blockchain": {
            "network": solana_result.get("network", "devnet"),
            "tx_id": solana_result.get("tx"),
            "anchored": solana_result.get("anchored", False),
            "explorer_url": solana_result.get("explorer"),
        },
        "timestamps": {
            "issued_utc": now.isoformat(),
            "issued_unix": int(now.timestamp()),
        },
        "verification": {
            "url": verify_url,
            "qr_code_url": f"{BASE_URL}/cert/{cert_id}/qr.png",
        },
        "legal": {
            "note": "This certificate constitutes cryptographic proof of content existence and ownership at the stated timestamp. Blockchain record is immutable and tamper-evident.",
            "patent": "Patent Pending — US Provisional Application Filed March 11, 2026",
        }
    }

    # Save cert JSON
    os.makedirs(CERT_DIR, exist_ok=True)
    cert_path = os.path.join(CERT_DIR, f"{cert_id}.json")
    with open(cert_path, "w") as f:
        json.dump(cert, f, indent=2)

    return cert


def build_certificate(
    creator_name: str,
    creator_email: str,
    creator_id: str,
    filename: str,
    sha256_hash: str,
    perceptual_hash: Optional[str],
    file_size_bytes: int,
    solana_result: dict,
) -> dict:
    """Full certificate generation pipeline."""
    cert_id = generate_cert_id()
    generate_qr_code(cert_id)
    cert = generate_certificate_json(
        cert_id=cert_id,
        creator_name=creator_name,
        creator_email=creator_email,
        filename=filename,
        sha256_hash=sha256_hash,
        perceptual_hash=perceptual_hash,
        file_size_bytes=file_size_bytes,
        solana_result=solana_result,
    )
    cert["creator_id"] = creator_id
    return cert
