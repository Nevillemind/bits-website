# BITS Authentication Strategy
_Decided: March 21, 2026 — George Mundin_

## Core Philosophy
**Match the experience of traditional financial software** (Charles Schwab, eToro, Fidelity).
Do NOT require crypto-native UX (authenticator apps, hardware wallets) for non-crypto SMB clients.
Goal: smooth onboarding, familiar experience, real security where money moves.

---

## Auth Tiers by Product

### Tier 1 — Standard Login Only
**Products:** Field Coach, BITS Halo
- Username + password
- "Remember this device" (30-day cookie)
- No extra steps — these are business tools, not wallets

### Tier 2 — New Device Verification
**Products:** TRACE, Shield, DataVault
- Normal login from known device → just password, straight in
- New browser/device → automatic email with 6-digit code (one-time)
- After verification, device is remembered for 30 days
- This is exactly what banks do — users already know this pattern

### Tier 3 — Transaction Confirmation
**Products:** Settld, TORA Exchange
- Login itself = standard (Tier 2 pattern)
- Sensitive actions (release escrow, confirm payment above threshold) → email/SMS confirmation code
- "You're about to release $2,800 to the supplier. Confirm: [6-digit code]"
- Matches Schwab/Fidelity wire transfer UX exactly

---

## What We Are NOT Doing
- ❌ Mandatory Google Authenticator / Authy app
- ❌ Hardware wallet connection required
- ❌ QR code scanning on every login
- ❌ Crypto wallet popup on login page
- ❌ Anything that feels like "crypto friction"

---

## Implementation Pattern (for dev reference)
1. **Device fingerprint** stored in httpOnly cookie on successful login
2. **New device detection** = fingerprint not in user's known devices list
3. **Email code** = 6-digit TOTP-style code, 10-minute expiry, sent via SMTP
4. **Transaction threshold** = configurable per product (e.g., >$500 for Settld)
5. **Known devices** stored in DB per user, expire after 30 days of inactivity

---

## Why This Works for Jeremy and Enterprise Clients
- Meets SOC 2 "multi-factor" requirements for sensitive transactions (Tier 3)
- Familiar UX = faster client onboarding
- No app downloads = no support tickets about "how do I set up 2FA"
- Still protects against account takeover (new device always triggers verification)
