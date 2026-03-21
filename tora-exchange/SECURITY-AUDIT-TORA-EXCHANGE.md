# TORA Exchange — Red Team Security Audit

**Auditor:** Doug Digital (Red Team Mode)  
**Date:** March 14, 2026  
**Scope:** Full codebase static analysis — all HTML portals, authentication logic, smart contracts, wallet files, infrastructure config  
**Classification:** CONFIDENTIAL — Internal Use Only

---

## Executive Summary

TORA Exchange has **6 CRITICAL vulnerabilities**, **8 HIGH**, **6 MEDIUM**, and **4 LOW** findings. The application is **not production-ready** in its current state. The authentication system is entirely client-side with hardcoded credentials visible in page source. The admin portal is accessible to anyone with a browser and a URL. A private key file is tracked by Git. These are not theoretical risks — they are active, exploitable vulnerabilities that would result in full platform compromise within minutes of deployment.

The smart contract code is structurally sound for a prototype but has meaningful gaps for production use. The most critical issues are in the web layer, not the blockchain layer.

---

## Vulnerability Summary Table

| ID | Severity | Title | File |
|----|----------|-------|------|
| V-01 | 🔴 CRITICAL | Hardcoded credentials in client-side JavaScript | signin.html |
| V-02 | 🔴 CRITICAL | Authentication is entirely client-side — trivially bypassed | signin.html |
| V-03 | 🔴 CRITICAL | No auth guards on any portal page — direct URL access | portal-admin/buyer/supplier.html |
| V-04 | 🔴 CRITICAL | Solana wallet private key tracked in Git | tora-exchange-wallet.json |
| V-05 | 🔴 CRITICAL | Admin credential hardcoded in HTML source | portal-admin.html |
| V-06 | 🔴 CRITICAL | Live API key hardcoded in buyer portal | portal-buyer.html |
| V-07 | 🟠 HIGH | No Content Security Policy — XSS has full run of the DOM | All HTML files |
| V-08 | 🟠 HIGH | Complete role separation failure — horizontal privilege escalation | All portals |
| V-09 | 🟠 HIGH | CoinGecko API response injected via innerHTML — XSS vector | index.html |
| V-10 | 🟠 HIGH | Smart contract: no license expiry enforcement on-chain | lib.rs |
| V-11 | 🟠 HIGH | Smart contract: unconstrained partner_treasury account | lib.rs |
| V-12 | 🟠 HIGH | Smart contract: no revoke/cancel mechanism — payments trapped if dispute | lib.rs |
| V-13 | 🟠 HIGH | Platform wallet address exposed in multiple portals | portal-supplier/admin.html |
| V-14 | 🟠 HIGH | No rate limiting on sign-in — brute force possible | signin.html |
| V-15 | 🟡 MEDIUM | Demo credentials permanently baked into JS — persist to production | signin.html |
| V-16 | 🟡 MEDIUM | Waitlist form never submits data — lead capture is dead | waitlist.html |
| V-17 | 🟡 MEDIUM | Contact form has no backend — dead form | contact.html |
| V-18 | 🟡 MEDIUM | Smart contract: month duration uses 30-day approximation | lib.rs |
| V-19 | 🟡 MEDIUM | Smart contract placeholder program ID not replaced before deploy | lib.rs |
| V-20 | 🟡 MEDIUM | No CSRF protection on any forms | All forms |
| V-21 | 🔵 LOW | No clickjacking protection (missing X-Frame-Options) | All HTML |
| V-22 | 🔵 LOW | No X-Content-Type-Options or HSTS headers | Server config |
| V-23 | 🔵 LOW | Supplier upload form has no file type validation in UI | portal-supplier.html |
| V-24 | 🔵 LOW | Smart contract: no close/reclaim instruction for accounts | lib.rs |

---

## CRITICAL FINDINGS

---

### V-01 — 🔴 CRITICAL: Hardcoded Credentials in Client-Side JavaScript

**File:** `signin.html`, lines ~162–166  
**CVSS Score:** 9.8 (Critical)

**Description:**  
All three role credentials — supplier, buyer, AND admin — are hardcoded in plain JavaScript visible to any user who hits View Source or opens DevTools. This is not protected behind any obfuscation layer.

```javascript
// FROM signin.html — VISIBLE IN BROWSER SOURCE
const demoAccounts = {
  supplier: { email: 'supplier@demo.com', password: 'demo2026', redirect: 'portal-supplier.html' },
  buyer:    { email: 'buyer@demo.com',    password: 'demo2026', redirect: 'portal-buyer.html' },
  admin:    { email: 'admin@toraexchange.com', password: 'admin2026', redirect: 'portal-admin.html' }
};
```

**Attack Steps:**
1. Visit `signin.html`
2. Press `Ctrl+U` (View Source) or open DevTools → Sources
3. Search for `demoAccounts` — credentials are immediately visible
4. Use `admin@toraexchange.com` / `admin2026` to sign in as admin

**Impact:** Full admin access. Attacker sees all supplier/buyer company data, revenue figures, contact emails, can manipulate platform settings, approve/deny suppliers.

**Fix:**
```javascript
// REMOVE demoAccounts object entirely from client-side code.
// Authentication must be performed server-side:
async function handleSignIn(e) {
  e.preventDefault();
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',  // for httpOnly cookie
    body: JSON.stringify({ email, password, role: activeRole })
  });
  if (response.ok) {
    const { redirectUrl } = await response.json();
    window.location.href = redirectUrl;
  } else {
    showError('Invalid credentials');
  }
}
```

---

### V-02 — 🔴 CRITICAL: Authentication Is 100% Client-Side

**File:** `signin.html`, lines ~168–177  
**CVSS Score:** 9.8 (Critical)

**Description:**  
The "authentication" check compares user input against the hardcoded `demoAccounts` object in JavaScript, then writes the role to `localStorage`. Any attacker can skip the signin page entirely by executing 2 lines of JavaScript in the browser console:

```javascript
// Attack — run in browser console, no credentials required:
localStorage.setItem('tora_role', 'admin');
localStorage.setItem('tora_user', 'admin@toraexchange.com');
```

Then navigate to `portal-admin.html`. Access granted.

**Impact:** Full admin portal access with zero credentials. No network requests made. No server to stop it.

**Fix:** Implement server-side sessions with `httpOnly` cookies. The server must validate a session token on every protected page load. `localStorage` must never be used for auth state.

---

### V-03 — 🔴 CRITICAL: No Auth Guards on Portal Pages

**File:** `portal-admin.html`, `portal-buyer.html`, `portal-supplier.html`  
**CVSS Score:** 9.8 (Critical)

**Description:**  
None of the three portal HTML files contain any authentication check. There is no JavaScript that reads `localStorage`, checks for a session cookie, or redirects unauthenticated users. Simply typing the URL in the address bar serves the full portal.

```
Attack URL:
https://toraexchange.com/portal-admin.html    → Full admin portal, no login required
https://toraexchange.com/portal-buyer.html    → Full buyer portal, no login required
https://toraexchange.com/portal-supplier.html → Full supplier portal, no login required
```

**Proof:** A grep across all three portal files for `localStorage`, `sessionStorage`, `checkAuth`, `tora_role` returns ZERO results. There is no auth check code anywhere in these files.

**Impact:** Every portal is fully publicly accessible. The admin command center is a static HTML page — zero authentication required.

**Fix:** Every protected page must have a session check as the **first** executed JavaScript:
```javascript
// Add to the top of every portal's <script> block:
(async function() {
  const res = await fetch('/api/auth/me', { credentials: 'include' });
  if (!res.ok) window.location.replace('/signin.html');
  const user = await res.json();
  if (user.role !== 'admin') window.location.replace('/signin.html');
})();
```
This must be a server call — not a localStorage read.

---

### V-04 — 🔴 CRITICAL: Solana Wallet Private Key Tracked in Git

**File:** `tora-exchange-wallet.json`  
**CVSS Score:** 10.0 (Critical)

**Description:**  
The file `tora-exchange-wallet.json` contains a 64-byte raw Solana keypair (the standard Solana CLI format). It is **confirmed to be tracked by Git** (`git ls-files` returns it; `git status` shows it as committed). There is no `.gitignore` in the `tora-exchange/` directory.

```
git ls-files bits/tora-exchange/tora-exchange-wallet.json
→ bits/tora-exchange/tora-exchange-wallet.json  ← TRACKED. COMMITTED. IN HISTORY.
```

The file content is the raw byte array `[244, 64, 199, ...]` — a valid Solana private key. The public key derived from this keypair is `9nqb1rUaP6UfXMCbA6mpwppBvPNmpRSiN4J2stG7yXSG` — the same wallet address displayed in `portal-admin.html` (Payment Settings) and `portal-supplier.html` (Revenue panel).

**Attack Steps:**
1. Clone the repo (if/when pushed to GitHub)
2. Open `tora-exchange-wallet.json` — private key is there in plaintext
3. Import to Phantom Wallet or Solana CLI: `solana-keygen recover --outfile stolen.json`
4. Drain any SOL, USDC, or tokens held by `9nqb1rUaP6UfXMCbA6mpwppBvPNmpRSiN4J2stG7yXSG`

**Impact:** Complete loss of any funds held in the TORA Exchange operational wallet. This is a **permanent, irreversible** financial loss. The key cannot be un-leaked once pushed to a remote.

**Immediate Actions Required:**
1. **Rotate the key NOW.** Generate a new keypair, transfer any funds to the new wallet
2. Add `.gitignore` to `tora-exchange/` directory with `*.json` excluded (or specifically `tora-exchange-wallet.json`)
3. Rewrite Git history to remove the file from all commits: `git filter-branch` or `git filter-repo`
4. Never store wallet private keys anywhere in the project directory

```bash
# Immediate fix:
echo "tora-exchange-wallet.json" >> /Users/georgemundin/.openclaw/workspace/bits/tora-exchange/.gitignore

# Then remove from Git history entirely:
git filter-repo --path bits/tora-exchange/tora-exchange-wallet.json --invert-paths
# OR use BFG Repo Cleaner:
bfg --delete-files tora-exchange-wallet.json
```

---

### V-05 — 🔴 CRITICAL: Admin Credentials Hardcoded in Admin Portal HTML

**File:** `portal-admin.html`, sidebar footer section  
**CVSS Score:** 8.5 (Critical)

**Description:**  
The admin portal hardcodes the admin's real email (`admin@toraexchange.com`) directly in the HTML, displayed in the sidebar footer. While this is cosmetic in a static demo, if this transitions to a real application backed by a real auth system, the admin email is permanently visible to anyone who accesses the page (which, per V-03, requires zero authentication).

```html
<!-- From portal-admin.html sidebar footer -->
<div class="user-name">George Mundin</div>
<div class="user-email">admin@toraexchange.com</div>
```

**Impact:** The admin's real name and email are exposed. Combined with V-01 (hardcoded admin password `admin2026`), this is a complete credential package for anyone who reads the source.

**Fix:** Remove hardcoded identity data from HTML. Populate from authenticated session after login.

---

### V-06 — 🔴 CRITICAL: Live API Key Hardcoded in Buyer Portal

**File:** `portal-buyer.html`, line 285  
**CVSS Score:** 9.0 (Critical)

**Description:**  
A string labeled `tora_live_sk_` — indicating a **production/live** API key — is hardcoded directly in the buyer portal HTML. Anyone who loads the page (which requires no login per V-03) sees the key in plain text.

```html
<!-- From portal-buyer.html — visible to ANY unauthenticated visitor -->
<div class="api-key">tora_live_sk_7f3a8b2c9d1e4f5a6b7c8d9e0f1a2b3c4d5e6f7a</div>
```

The naming convention `tora_live_sk_` matches production API key patterns (similar to Stripe's `sk_live_` prefix). If this key is live and backed by a real API, any attacker can:
- Query all licensed datasets
- Pull record-level data
- Impersonate Vanguard Partners (the demo buyer identity)
- Renew licenses without authorization

**Fix:**
- Remove API keys from all HTML templates
- API keys must be served to authenticated users only, via a secure API call after login
- Rotate this key immediately if it's active
- Implement key scoping (per-user, per-dataset keys with expiry)

---

## HIGH FINDINGS

---

### V-07 — 🟠 HIGH: No Content Security Policy — XSS Has Full DOM Access

**Files:** All HTML files  
**CVSS Score:** 7.4

**Description:**  
No file in the TORA Exchange project contains a Content Security Policy (CSP) header or meta tag. CSP is the primary defense against cross-site scripting (XSS) attacks. Without it, any injected script can:
- Exfiltrate `localStorage` (session tokens, API keys)
- Make authenticated API calls on behalf of the victim
- Redirect to phishing pages
- Steal clipboard contents, keystrokes

**Fix:**
```html
<!-- Add to <head> of every page -->
<meta http-equiv="Content-Security-Policy" content="
  default-src 'self';
  script-src 'self';
  style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;
  font-src https://fonts.gstatic.com;
  img-src 'self' https://images.unsplash.com;
  connect-src 'self' https://api.coingecko.com;
  frame-ancestors 'none';
">
```

Or set via server HTTP headers (preferred).

---

### V-08 — 🟠 HIGH: Complete Role Separation Failure

**Files:** All portal HTML files  
**CVSS Score:** 8.1

**Description:**  
There is zero enforcement of role-based access between portals. A supplier who legitimately authenticated (via the demo credentials) can freely navigate to `portal-buyer.html` or `portal-admin.html`. A buyer can access the supplier upload panel. The concept of "buyer," "supplier," and "admin" as distinct roles exists only in the UI cosmetics — not in any access control layer.

**Attack:**  
Log in as `supplier@demo.com` → navigate to `portal-admin.html` → full admin access.  
Log in as `buyer@demo.com` → navigate to `portal-supplier.html` → see all supplier revenue data, upload data.

**Impact:** Complete horizontal privilege escalation. Any user, in any role, can access any other role's portal.

**Fix:** Server-side role enforcement. Every page load must verify the authenticated user's role matches the required role for that page. This cannot be a client-side check.

---

### V-09 — 🟠 HIGH: CoinGecko API Response Injected via innerHTML

**File:** `index.html`, lines ~1937–1945  
**CVSS Score:** 7.5

**Description:**  
The ticker component builds HTML from CoinGecko API response data and assigns it to `track.innerHTML`. While price data is passed through `Number.toFixed()` (which prevents direct script injection via numeric fields), the data structure keys are iterated via `Object.entries(symbols)` — if a MITM or compromised CDN could influence the symbols object, arbitrary HTML would be injected.

More concretely: **the current code is one DNS hijack or CDN compromise away from stored XSS on the homepage**.

```javascript
// From index.html — innerHTML with API-sourced data
html += `<span class="ticker-item">
  <span class="ticker-label">${sym}</span>  ← sym from hardcoded symbols object (OK now)
  <span class="ticker-value">${price}       ← fmt() processes numbers (mostly safe)
    <span class="${cls}">${sign}${chg.toFixed(1)}% ${arrow}</span>
  </span>
</span>`;
track.innerHTML = html;  // ← innerHTML = XSS risk
```

**Fix:** Replace `innerHTML` with safe DOM construction:
```javascript
// Use textContent for all user/API-derived values:
const item = document.createElement('span');
item.className = 'ticker-item';
// ... build with createElement, textContent, appendChild
track.appendChild(item);
```

Or use a sanitization library like DOMPurify if innerHTML is required.

---

### V-10 — 🟠 HIGH: Smart Contract — No License Expiry Enforcement

**File:** `tora-contracts/programs/tora-contracts/src/lib.rs`, lines 153–165  
**CVSS Score:** 8.0

**Description:**  
The smart contract stores `expires_at` as a Unix timestamp on the `License` account, but **no instruction ever checks whether a license has expired**. There is no `verify_license` instruction, no `check_access` function, and no instruction that reads `expires_at` against the current clock.

```rust
// lib.rs stores expiry but NEVER enforces it:
license.expires_at = Clock::get()?.unix_timestamp + (license_months as i64 * 30 * 24 * 60 * 60);
license.is_active = true;
// ← No instruction ever checks: if Clock::get()?.unix_timestamp > license.expires_at { error! }
```

**Impact:**  
- Buyers can continue accessing data after their license expires — platform loses revenue
- The platform's "automatic expiry" claim to suppliers is false — suppliers continue obligations with no payment
- On-chain license records say `is_active = true` forever (there's no deactivation instruction either)

**Fix:**
```rust
// Add a verify_license instruction:
pub fn verify_license(ctx: Context<VerifyLicense>) -> Result<()> {
    let license = &mut ctx.accounts.license;
    let now = Clock::get()?.unix_timestamp;
    require!(license.is_active, ToraError::LicenseInactive);
    if now > license.expires_at {
        license.is_active = false;
        return err!(ToraError::LicenseExpired);
    }
    Ok(())
}

// Add error:
#[error_code]
LicenseExpired,
LicenseInactive,
```

---

### V-11 — 🟠 HIGH: Smart Contract — Unconstrained `partner_treasury` Account

**File:** `tora-contracts/programs/tora-contracts/src/lib.rs`, lines 322–324  
**CVSS Score:** 8.5

**Description:**  
The `PurchaseLicense` instruction accepts a `partner_treasury` account with only `/// CHECK:` annotation and no constraint on which address is valid. This means the transaction caller can pass **any wallet address** as `partner_treasury` — including their own — and receive the 5% partner fee on every transaction.

```rust
// lib.rs — partner_treasury is completely unconstrained:
/// CHECK: Partner treasury receives fee
#[account(mut)]
pub partner_treasury: AccountInfo<'info>,  // ← No constraint! Any wallet accepted!
```

Compare to `platform_treasury` which has a constraint:
```rust
/// CHECK: Platform treasury receives fee
#[account(mut, constraint = platform_treasury.key() == exchange.authority @ ToraError::WrongTreasury)]
pub platform_treasury: AccountInfo<'info>,  // ← Constrained to exchange.authority ✓
```

**Attack:**  
Craft a `purchase_license` transaction passing your own wallet as `partner_treasury`. 5% of every license fee is diverted to the attacker. On a $9,200/mo healthcare license, that's $460 per transaction stolen from the legitimate partner.

**Fix:**
```rust
// Add partner_treasury to Exchange account state:
pub partner_treasury: Pubkey,  // set at initialize()

// Add constraint to PurchaseLicense context:
#[account(mut, constraint = partner_treasury.key() == exchange.partner_treasury @ ToraError::WrongPartnerTreasury)]
pub partner_treasury: AccountInfo<'info>,
```

---

### V-12 — 🟠 HIGH: Smart Contract — No Dispute/Refund/Cancel Mechanism

**File:** `tora-contracts/programs/tora-contracts/src/lib.rs`  
**CVSS Score:** 7.0

**Description:**  
The smart contract has no ability to handle disputes, issue refunds, or cancel an active license. Once a buyer pays, the funds are immediately transferred to the supplier, platform, and partner — irreversibly. There is no:
- `cancel_license` instruction
- `issue_refund` instruction  
- Admin override capability for fraudulent transactions
- Escrow period (funds release immediately, not upon verified delivery)

This contradicts the TORA security posture doc which says "smart contract releases funds automatically" after "Supplier fulfills" — but the current contract transfers funds the moment `purchase_license` is called, with no verification of fulfillment.

**Impact:**  
- Supplier lists garbage data, buyer pays — no recourse
- Fraudulent listing → payment is instant and irreversible
- Platform has zero ability to protect buyers from bad actors
- Regulatory risk: regulators may view this as facilitating fraud

**Fix:** Implement an escrow pattern:
```rust
// Funds go into a PDA escrow account, not directly to supplier:
// purchase_license → funds held in escrow PDA
// release_payment (called by buyer after verifying data) → funds released to supplier
// cancel_and_refund (called within 48h window) → funds returned to buyer
// admin_resolve (called by exchange.authority) → arbitration override
```

---

### V-13 — 🟠 HIGH: Platform Wallet Address Exposed in Portals

**Files:** `portal-supplier.html` (line 391), `portal-admin.html` (Payment Settings panel)  
**CVSS Score:** 7.5

**Description:**  
The full Solana wallet address `9nqb1rUaP6UfXMCbA6mpwppBvPNmpRSiN4J2stG7yXSG` is hardcoded in two portal HTML files. This is the same wallet whose private key is in `tora-exchange-wallet.json` — which is tracked by Git (V-04). 

An attacker who finds the private key in Git history can immediately verify which wallet to target by reading the portal HTML. The wallet address and private key are co-located in the same repository.

**Fix:** Remove hardcoded wallet addresses from HTML. Serve them only to authenticated users via API. Rotate the wallet key.

---

### V-14 — 🟠 HIGH: No Rate Limiting on Sign-In

**File:** `signin.html`  
**CVSS Score:** 7.3

**Description:**  
The sign-in form has no rate limiting, no CAPTCHA, no account lockout, and no brute-force protection. Since authentication is client-side, there's no server even involved — but when a real backend is implemented, this must be addressed. Currently there is zero friction for credential stuffing attacks.

**Fix:** Implement server-side rate limiting (max 5 attempts per IP per 15 minutes), exponential backoff, CAPTCHA after 3 failures, account lockout after 10 failures.

---

## MEDIUM FINDINGS

---

### V-15 — 🟡 MEDIUM: Demo Credentials Permanently Baked into JS

**File:** `signin.html`  
**Description:**  
Even if the platform transitions to production, the `demoAccounts` object with `demo2026` passwords will remain in the source unless explicitly removed. This is a ticking timebomb — demo credentials frequently become production backdoors. The password `demo2026` against `admin@toraexchange.com` will work forever unless the code is changed.

**Fix:** Remove the `demoAccounts` object entirely. Implement separate, time-limited demo tokens for trade shows / investor demos that can be revoked.

---

### V-16 — 🟡 MEDIUM: Waitlist Form Never Submits Data

**File:** `waitlist.html`, line 780  
**Description:**  
The waitlist form's submit handler shows a success message but never sends data anywhere — no `fetch()`, no `XMLHttpRequest`, no backend call. User leads are silently discarded.

```javascript
function submitWaitlist(e) {
  e.preventDefault();
  document.getElementById('form-content').style.display = 'none';
  document.getElementById('success-state').style.display = 'block';
  // ← No data ever sent. Leads are lost.
}
```

**Impact:** Every person who signs up for the waitlist gets a "success" message but their contact information is never captured. This is a business impact, not just a security issue.

**Fix:** Integrate with a form backend (Airtable, Typeform, or custom API endpoint).

---

### V-17 — 🟡 MEDIUM: Contact Form Has No Backend

**File:** `contact.html`  
**Description:**  
The contact form has no `action` attribute, no `onsubmit` handler, no JavaScript handler. Submitting it does nothing. Messages from potential enterprise clients are silently dropped.

**Fix:** Add a form submission handler that POSTs to a backend endpoint or third-party service.

---

### V-18 — 🟡 MEDIUM: Smart Contract Month Duration Approximation

**File:** `tora-contracts/programs/tora-contracts/src/lib.rs`, line 163  
**Description:**  
License expiry is calculated as `license_months * 30 * 24 * 60 * 60`. Using 30 days as a month is incorrect — months range from 28 to 31 days. A 3-month license (92 days actual) would expire 90 days after purchase, 2 days early. For a $9,200/month healthcare license, that's ~$600 in under-service per contract year.

**Fix:** Use a calendar-aware month calculation or standardize on 30-day billing cycles explicitly documented in the license terms.

---

### V-19 — 🟡 MEDIUM: Placeholder Program ID in Smart Contract

**File:** `tora-contracts/programs/tora-contracts/src/lib.rs`, line 4  
**Description:**  
```rust
declare_id!("11111111111111111111111111111111"); // Will be replaced with real Program ID on deploy
```
The System Program address (`111...111`) is used as a placeholder. Deploying without updating this will result in a program that cannot be invoked correctly, or worse, may conflict with Solana's native system program.

**Fix:** Run `anchor build` → capture the generated program ID → update `declare_id!()` before any deployment.

---

### V-20 — 🟡 MEDIUM: No CSRF Protection on Forms

**Files:** `waitlist.html`, `contact.html`, portal forms  
**Description:**  
No form uses CSRF tokens. When a backend is implemented, all state-changing form submissions must include a server-generated CSRF token to prevent cross-site request forgery attacks.

**Fix:** Implement synchronizer token pattern or use SameSite=Strict cookies (which provides some CSRF protection natively).

---

## LOW FINDINGS

---

### V-21 — 🔵 LOW: No Clickjacking Protection

**Files:** All HTML files  
**Description:**  
No pages include `X-Frame-Options: DENY` or the CSP `frame-ancestors 'none'` directive. Pages can be embedded in iframes on attacker-controlled sites to perform clickjacking attacks.

**Fix:** Add `X-Frame-Options: DENY` to all HTTP responses, or include `frame-ancestors 'none'` in CSP.

---

### V-22 — 🔵 LOW: Missing Security Headers

**Files:** Server configuration (not present)  
**Description:**  
The following security headers are absent. No server configuration files (nginx.conf, vercel.json, netlify.toml, _headers) were found in the repository.

| Header | Purpose |
|--------|---------|
| `X-Content-Type-Options: nosniff` | Prevents MIME sniffing |
| `Strict-Transport-Security` | Forces HTTPS |
| `Referrer-Policy: strict-origin-when-cross-origin` | Controls referrer leakage |
| `Permissions-Policy` | Restricts browser features |

**Fix:** Add a server config file (e.g., `_headers` for Netlify, `vercel.json` for Vercel) with these headers.

---

### V-23 — 🔵 LOW: Supplier Upload Form Has No Client-Side File Validation

**File:** `portal-supplier.html`, Upload panel  
**Description:**  
The upload form accepts "CSV, Excel, PDF, ZIP (max 500MB)" per the UI text, but there is no JavaScript validation of file type or size. Malicious files could be uploaded. This is low severity because backend validation must also exist, but defense-in-depth requires client-side checks too.

**Fix:** Add JavaScript file type and size validation before allowing submission.

---

### V-24 — 🔵 LOW: Smart Contract — No Account Close Instructions

**File:** `tora-contracts/programs/tora-contracts/src/lib.rs`  
**Description:**  
There are no `close` instructions for any account type (License, Listing). Expired licenses and deactivated listings permanently occupy account space on-chain, with no way to reclaim the SOL rent. As the platform scales, this becomes a meaningful operational cost.

**Fix:** Add `close = recipient` to account contexts for accounts that should be closeable.

---

## Comparison: TORA-SECURITY-POSTURE.md vs Reality

The existing security posture document makes several claims worth fact-checking:

| Claim | Reality |
|-------|---------|
| "Smart contract escrow — funds released after supplier fulfills" | ❌ FALSE. Funds transfer immediately on purchase. No escrow, no fulfillment verification. |
| "TORA is never in possession of your funds" | ✅ Largely true for user funds in the smart contract design |
| "A breach of our servers means nothing" | ❌ MISLEADING. The server may not hold private keys, but it holds admin credentials (in JS source), API keys, and if the wallet private key is on the server filesystem, that changes everything |
| "2FA on admin panels — on hold until post-demo phase" | ⚠️ There is no admin panel auth at all, let alone 2FA |
| "Bug bounty program after codebase stabilizes" | The codebase currently has 6 CRITICAL vulnerabilities — do not go public without fixing these first |

---

## Priority Remediation Roadmap

### Immediate (Before Any Public Demo or Deployment)

1. **V-04** — Rotate the wallet key. Remove `tora-exchange-wallet.json` from Git history. Add to `.gitignore`.
2. **V-01/V-02** — Remove `demoAccounts` from `signin.html`. All auth must be server-side.
3. **V-03** — Add auth guards to every portal page.
4. **V-06** — Remove the hardcoded API key from `portal-buyer.html`. Rotate it if live.
5. **V-11** — Add constraint to `partner_treasury` in the smart contract.

### Before Beta / Investor Demo

6. **V-08** — Implement server-side role-based access control
7. **V-07** — Add Content Security Policy to all pages
8. **V-10** — Implement license expiry enforcement in the smart contract
9. **V-09** — Replace `innerHTML` with safe DOM construction in the ticker
10. **V-12** — Design and implement escrow + dispute flow
11. **V-16/V-17** — Fix the dead forms (waitlist and contact)

### Before Production Launch

12. **V-14** — Rate limiting on authentication
13. **V-15** — Remove all demo credentials from source permanently
14. **V-18/V-19** — Fix contract month calculation and program ID
15. **V-20** — CSRF tokens on all forms
16. **V-21/V-22** — Security headers
17. **V-24** — Add account close instructions

---

## Final Assessment

The current TORA Exchange codebase is a **well-designed prototype** with strong visual polish and solid smart contract architecture. The business logic is clear and the Anchor/Solana code is structurally sound. However, **it has zero production security**. The auth system is decorative — it stops no one. The portals are public URLs. The private key is in Git.

None of this is unusual for a pre-alpha product being built to demonstrate concept. These issues are fixable. But the path from "impressive demo" to "exchange handling real business data and real payments" requires a full auth overhaul, smart contract hardening, and operational security practices.

The most dangerous thing would be pushing this repo to a public GitHub before fixing V-04. Once that private key is public, it cannot be un-leaked.

---

*Audit prepared by Doug Digital | Red Team security analysis | March 14, 2026*  
*This document is CONFIDENTIAL — share only within the BITS core team*
