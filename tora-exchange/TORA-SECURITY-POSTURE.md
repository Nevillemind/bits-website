# TORA Exchange — Security Posture & Messaging

**Last Updated:** March 13, 2026
**Author:** Doug Digital (George Mundin direction)

---

## The Two Foundational Security Truths

These are not just marketing statements. They are architectural commitments built into how TORA operates. Both are displayed prominently on the website.

---

### 1. Smart Contract Escrow — TORA Never Holds Funds

Every transaction on TORA is governed entirely by smart contracts deployed on the Solana blockchain.

- Buyer places order → funds go directly into a **tamper-proof smart contract**
- Supplier fulfills → smart contract releases funds automatically
- TORA is the **marketplace facilitator**, not the bank or escrow agent
- No TORA employee — ever, under any circumstance — can access or move user funds
- This is the same model used by Uniswap, OpenSea, and every serious DeFi platform

**Legal implication:** TORA is not a money transmitter. We do not hold, transfer, or control funds. The blockchain is the escrow agent. This significantly reduces regulatory liability.

**Customer message:**
> *"TORA is the marketplace. The blockchain is the bank. We are never in possession of your funds."*

---

### 2. A Breach of Our Servers Means Nothing

TORA's servers contain:
- Marketplace listings and categories
- User profiles and company information
- Transaction history and order records
- Platform analytics

TORA's servers do NOT contain:
- Wallet private keys (users hold these in their own wallets)
- Funds or cryptocurrency (in smart contracts on-chain)
- Decryption credentials for user data

**What this means:** Even in the worst-case scenario — a complete compromise of TORA infrastructure — an attacker cannot access, move, or steal any user funds. The blockchain holds the assets. The servers hold the marketplace experience. Those are two separate things.

**Customer message:**
> *"Even if our servers were completely breached, there is nothing an attacker could steal from your wallet or funds. The blockchain holds your assets — not us."*

---

## Architecture Summary

```
User Wallet (Phantom/MetaMask)
    ↓ connects to
TORA Marketplace (our servers)
    ↓ initiates transaction via
Solana Smart Contract (on-chain, not our servers)
    ↓ upon fulfillment, automatically releases to
Supplier Wallet
```

TORA's server is never in the financial transaction path. It only facilitates the connection and provides the marketplace interface.

---

## Future Security Roadmap (not yet implemented)

- [ ] 2FA on admin panels (hold until post-demo phase)
- [ ] Cloudflare WAF hardening
- [ ] Smart contract audit before mainnet financial transactions
- [ ] Multi-signature wallets for any operational funds TORA holds (e.g., fee collection)
- [ ] SOC 2 compliance (when enterprise clients demand it)
- [ ] Bug bounty program (after codebase stabilizes)
- [ ] Insurance for custodied operational funds

---

## Key Messaging Locations on Website

1. **"Security Architecture" section** on `index.html` — between Trust and Insights sections
2. **Terms of Service** — non-custodial language should be added
3. **Buyer Guide** — explain smart contract escrow flow
4. **Supplier Guide** — confirm funds release mechanism

---

*Note: George's direction (March 13, 2026): "I think that should be on the exchange somewhere where people can really see that. I think that's a huge selling point and a comfort factor if someone just looked at the website."*
