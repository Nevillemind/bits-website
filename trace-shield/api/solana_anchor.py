"""
TRACE Shield — Solana Anchoring
Anchors SHA-256 + perceptual hash to Solana blockchain as immutable proof of content.

⚠️  TRANSACTION EXECUTION DISABLED by default.
    Set TRACE_SOLANA_ENABLED=true to activate (requires George's explicit approval).

Patent Pending — George Mundin / BITS
"""
import os
import json
import base64
import hashlib
from datetime import datetime, timezone
from typing import Optional

# Solana enabled flag — DISABLED until George explicitly approves
SOLANA_ENABLED = os.environ.get("TRACE_SOLANA_ENABLED", "false").lower() == "true"
SOLANA_NETWORK = os.environ.get("TRACE_SOLANA_NETWORK", "devnet")  # devnet | mainnet-beta

MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
RPC_ENDPOINTS = {
    "devnet": "https://api.devnet.solana.com",
    "mainnet-beta": "https://api.mainnet-beta.solana.com",
}


def build_memo_payload(cert_id: str, sha256: str, phash: Optional[str]) -> str:
    """Build the memo string to anchor on-chain."""
    payload = {
        "v": 1,
        "product": "TRACE-SHIELD",
        "cert": cert_id,
        "sha256": sha256,
        "phash": phash or "",
        "ts": datetime.now(timezone.utc).isoformat(),
    }
    return json.dumps(payload, separators=(",", ":"))


def anchor_to_solana(cert_id: str, sha256_hash: str, phash: Optional[str]) -> dict:
    """
    Anchor a certificate hash to Solana blockchain.
    Returns tx info if enabled, or a simulated record if disabled.
    """
    memo = build_memo_payload(cert_id, sha256_hash, phash)

    if not SOLANA_ENABLED:
        # Simulated anchor — blockchain code ready, tx execution awaiting George's approval
        sim_tx = "SIM_" + hashlib.sha256(memo.encode()).hexdigest()[:32]
        return {
            "anchored": False,
            "simulated": True,
            "network": SOLANA_NETWORK,
            "tx": sim_tx,
            "memo": memo,
            "note": "Solana tx disabled. Set TRACE_SOLANA_ENABLED=true to activate.",
        }

    # ── LIVE SOLANA ANCHORING ────────────────────────────────────────────────
    try:
        import httpx
        from solders.keypair import Keypair
        from solders.pubkey import Pubkey
        from solders.transaction import Transaction
        from solders.instruction import Instruction, AccountMeta
        from solders.message import Message
        from solders.hash import Hash

        # Load TRACE wallet
        wallet_path = os.path.join(os.path.dirname(__file__), "../../trace-mvp/core/bits-trace-wallet.json")
        with open(wallet_path) as f:
            wallet_data = json.load(f)
        keypair_bytes = bytes(wallet_data["keypair"])
        keypair = Keypair.from_bytes(keypair_bytes)

        rpc_url = RPC_ENDPOINTS[SOLANA_NETWORK]

        # Get latest blockhash
        resp = httpx.post(rpc_url, json={
            "jsonrpc": "2.0", "id": 1,
            "method": "getLatestBlockhash",
            "params": [{"commitment": "finalized"}]
        }, timeout=15)
        blockhash_data = resp.json()
        recent_blockhash = blockhash_data["result"]["value"]["blockhash"]

        # Build memo instruction
        memo_pubkey = Pubkey.from_string(MEMO_PROGRAM_ID)
        memo_data = memo.encode("utf-8")
        instruction = Instruction(
            program_id=memo_pubkey,
            accounts=[AccountMeta(pubkey=keypair.pubkey(), is_signer=True, is_writable=False)],
            data=memo_data
        )

        # Build and sign transaction
        msg = Message.new_with_blockhash([instruction], keypair.pubkey(), Hash.from_string(recent_blockhash))
        tx = Transaction.new_unsigned(msg)
        tx.sign([keypair], Hash.from_string(recent_blockhash))

        # Send transaction
        tx_bytes = bytes(tx)
        encoded = base64.b64encode(tx_bytes).decode()
        send_resp = httpx.post(rpc_url, json={
            "jsonrpc": "2.0", "id": 1,
            "method": "sendTransaction",
            "params": [encoded, {"encoding": "base64"}]
        }, timeout=20)
        result = send_resp.json()

        if "result" in result:
            tx_sig = result["result"]
            return {
                "anchored": True,
                "simulated": False,
                "network": SOLANA_NETWORK,
                "tx": tx_sig,
                "explorer": f"https://explorer.solana.com/tx/{tx_sig}?cluster={SOLANA_NETWORK}",
                "memo": memo,
            }
        else:
            raise Exception(f"Solana RPC error: {result.get('error')}")

    except Exception as e:
        return {
            "anchored": False,
            "simulated": True,
            "network": SOLANA_NETWORK,
            "tx": "ERROR_" + hashlib.sha256(memo.encode()).hexdigest()[:16],
            "error": str(e),
            "memo": memo,
        }
