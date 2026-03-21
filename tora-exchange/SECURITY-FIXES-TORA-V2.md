# TORA Exchange — Security Fixes V2 Summary

**Date:** March 21, 2026
**Performed by:** Security Engineering (automated remediation via OpenClaw)
**Audit source:** `AUDIT-REPORT-TORA-V2.md` (re-audit of March 21, 2026)
**Prior fixes:** `SECURITY-FIXES-TORA-EXCHANGE.md` (March 14, 2026)
**Status:** All targeted V2 findings addressed

---

## Fix 1 — NEW-02 + NEW-03: innerHTML XSS Eliminated Across All 7 Pages (HIGH)

**Problem:** The V-09 fix (safe DOM construction for the crypto ticker) was only applied to `index.html`. Seven other pages still used `innerHTML` with unsanitized CoinGecko API data, creating XSS vectors via MITM or DNS hijack.

**Affected files:**
- `index-v2.html` (line 1904: `track.innerHTML = html;`)
- `privacy.html` (line 213: `t.innerHTML = h;`)
- `terms.html` (line 213: `t.innerHTML = h;`)
- `buyer-guide.html` (line 340: `t.innerHTML = h;`)
- `supplier-guide.html` (line 259: `t.innerHTML = h;`)
- `careers.html` (line 122: `t.innerHTML=h;`)
- `press.html` (line 198: `t.innerHTML=h;`)

**Fix:**
- Created `js/safe-ticker.js` — a shared external script that builds the ticker using **only safe DOM methods**: `document.createElement()`, `textContent`, `appendChild()`, `createTextNode()`.
- Zero use of `innerHTML` anywhere in the file. Clearing is done via `track.textContent = ''`.
- Replaced all 7 inline ticker `<script>` blocks with `<script src="js/safe-ticker.js"></script>`.
- Maintains identical functionality: 12 coins, fallback cached data, 5s initial fetch, 120s refresh interval, double-loop for infinite scroll.
- Uses the same CSS classes (`ticker-item`, `ticker-label`, `ticker-value`, `ticker-up`, `ticker-down`, `ticker-dot`) for visual consistency.

**Verification:**
```
$ grep -r '\.innerHTML' *.html
→ 0 matches (confirmed clean)
```

---

## Fix 2 — NEW-06: Admin Email Placeholder Genericized (MEDIUM)

**Problem:** `portal-admin.html` had `placeholder="admin@toraexchange.com"` in the admin email input field, leaking the admin email format in source/UI.

**File:** `portal-admin.html`, line 474

**Fix:** Changed placeholder from `admin@toraexchange.com` to `admin@company.com`.

**Verification:**
```
$ grep 'admin@toraexchange.com' portal-admin.html
→ 0 matches (confirmed clean)
```

---

## Fix 3 — SC-03: `raise_dispute` Instruction Added to Smart Contract

**Problem:** The smart contract had `is_disputed` on the License struct but no instruction to set it to `true`. The `admin_resolve` instruction could clear disputes, but no party could initiate one.

**File:** `tora-contracts/programs/tora-contracts/src/lib.rs`

**Fix:**
- Added `raise_dispute` instruction to the `#[program]` module.
- **Access control:** Only the buyer can call it (`license.buyer == buyer.key()` constraint via `Signer`).
- **Guard: already disputed** — Returns `AlreadyDisputed` error if `license.is_disputed == true`.
- **Guard: already released** — Returns `PaymentAlreadyReleased` error if `license.is_released == true`.
- Sets `license.is_disputed = true`, which blocks `release_payment` and `cancel_and_refund` (both check `!is_disputed`).
- Added `RaiseDispute` accounts context with buyer signer constraint.
- Added two new error codes to `ToraError` enum:
  - `AlreadyDisputed` — "License is already disputed"
  - `PaymentAlreadyReleased` — "Payment has already been released — cannot dispute"

**Dispute flow (now complete):**
1. Buyer calls `raise_dispute` → `is_disputed = true`
2. Release/cancel blocked while disputed
3. Admin calls `admin_resolve(release_to_supplier: bool)` → arbitrates funds, clears dispute

---

## Files Modified

| File | Change |
|------|--------|
| `js/safe-ticker.js` | **NEW** — Shared safe ticker script (no innerHTML) |
| `index-v2.html` | Inline ticker script → external `js/safe-ticker.js` |
| `privacy.html` | Inline ticker script → external `js/safe-ticker.js` |
| `terms.html` | Inline ticker script → external `js/safe-ticker.js` |
| `buyer-guide.html` | Inline ticker script → external `js/safe-ticker.js` |
| `supplier-guide.html` | Inline ticker script → external `js/safe-ticker.js` |
| `careers.html` | Inline ticker script → external `js/safe-ticker.js` |
| `press.html` | Inline ticker script → external `js/safe-ticker.js` |
| `portal-admin.html` | Placeholder `admin@toraexchange.com` → `admin@company.com` |
| `tora-contracts/.../lib.rs` | Added `raise_dispute` instruction + `RaiseDispute` context + 2 error codes |

---

## Remaining Items (Not Addressed in This Round)

| ID | Finding | Status | Notes |
|----|---------|--------|-------|
| NEW-04 | CSP `unsafe-inline` | Deferred | Requires migrating all inline scripts to external files + nonce-based CSP |
| NEW-05 | No SRI on Google Fonts | Deferred | Google Fonts dynamically generates CSS per user agent; self-hosting recommended |
| NEW-07 | Client-side hash salt | Accepted risk | Demo phase — production requires server-side bcrypt/argon2 |
| NEW-08 | Founder name in about/press | Accepted | Intentional public content |

---

*Fixes performed March 21, 2026 | Re-audit: AUDIT-REPORT-TORA-V2.md*
