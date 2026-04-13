"""
TRACE Shield — Hash Engine
Dual-hash system: SHA-256 (exact) + Perceptual Hash (fuzzy/deepfake detection)
Patent Pending — George Mundin / BITS
"""
import hashlib
import io
import struct
from typing import Optional
from PIL import Image
import imagehash


def sha256_hash(data: bytes) -> str:
    """Cryptographic hash — changes if even 1 bit is altered."""
    return hashlib.sha256(data).hexdigest()


def sha256_hash_file(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def perceptual_hash(data: bytes, content_type: str = "") -> Optional[str]:
    """
    Perceptual hash — stays similar even after re-encoding, compression, cropping.
    Works on images directly. For video, extracts first frame thumbnail if possible.
    Returns None if content cannot be perceptually hashed.
    """
    try:
        img = Image.open(io.BytesIO(data))
        phash = imagehash.phash(img)
        return str(phash)
    except Exception:
        pass

    # For video files, generate a deterministic hash from file structure
    # (full video perceptual hashing requires ffmpeg — Phase 2)
    try:
        # Use first 64KB as a structural fingerprint for video
        sample = data[:65536] if len(data) > 65536 else data
        h = hashlib.blake2b(sample, digest_size=8)
        return f"vid_{h.hexdigest()}"
    except Exception:
        return None


def perceptual_distance(hash1: str, hash2: str) -> Optional[int]:
    """
    Hamming distance between two perceptual hashes.
    0 = identical, < 10 = very similar (likely same content), > 20 = different
    """
    try:
        if hash1.startswith("vid_") or hash2.startswith("vid_"):
            return None  # Can't compare video structural hashes this way
        h1 = imagehash.hex_to_hash(hash1)
        h2 = imagehash.hex_to_hash(hash2)
        return h1 - h2
    except Exception:
        return None


def similarity_verdict(distance: Optional[int]) -> str:
    if distance is None:
        return "UNKNOWN"
    if distance == 0:
        return "EXACT_MATCH"
    if distance < 10:
        return "HIGHLY_SIMILAR"
    if distance < 20:
        return "SIMILAR"
    return "DIFFERENT"
