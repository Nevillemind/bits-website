# TORA Exchange — Smart Contract Architecture
*Blockchain Integrative Technology Solutions (B.I.T.S.)*
*Last Updated: March 9, 2026*

---

## Overview

The TORA Exchange smart contract is a native Solana program built with Anchor (Rust). It lives permanently on the Solana blockchain and handles all marketplace operations — asset listing, license purchasing, and automated revenue distribution. No centralized server controls the contract logic; it is immutable, auditable, and decentralized.

**Program Binary:** 265 KB (compiled Rust → BPF bytecode)
**Framework:** Anchor v0.32.1
**Language:** Rust 1.94.0
**Network:** Solana Mainnet-Beta
**TORA Wallet:** `3u5SRdybcbkAuqZzPh4bDN1t8PZbHT6czzoKoY19KSC8`

---

## Smart Contract Functions

### 1. `initialize`
**Called once** by the BITS admin to set up the exchange.
- Sets platform fee (default: 1500 bps = 15%)
- Sets partner fee (default: 500 bps = 5%)
- Creates the global Exchange config account
- Authority: TORA Exchange wallet (BITS)

### 2. `list_asset`
**Called by data suppliers** to register a tokenized data asset on the exchange.
- Accepts: asset ID, name, industry, price (per month), record count, data hash (SHA-256)
- Creates a Listing account on-chain with all metadata
- Each listing is a PDA (Program Derived Address) seeded by the asset ID
- Listing is publicly verifiable on Solana Explorer

### 3. `purchase_license`
**Called by data buyers** to license access to a listed asset.
- Buyer specifies license duration (1-12 months)
- Total payment = monthly price × months
- **Automatic revenue split via smart contract:**
  - **80% → Supplier** (data owner)
  - **15% → BITS Platform** (TORA Exchange treasury)
  - **5% → Data Partner** (referral/integration partner)
- Creates a License account on-chain recording all transaction details
- All splits are atomic — either all transfers succeed or none do

### 4. `deactivate_listing`
**Supplier-only.** Deactivates a listing so it cannot receive new license purchases.
- Only the original supplier wallet can call this
- Existing licenses remain valid until expiration

### 5. `update_fees`
**Admin-only.** Adjusts the platform and partner fee percentages.
- Maximum combined fee: 50% (5000 basis points)
- Only the exchange authority (BITS wallet) can call this

---

## On-Chain Data Structures

### Exchange (Global Config)
```
┌─────────────────────────┬──────────────┐
│ Field                   │ Type         │
├─────────────────────────┼──────────────┤
│ authority               │ Pubkey (32b) │
│ platform_fee_bps        │ u16 (2b)     │
│ partner_fee_bps         │ u16 (2b)     │
│ total_listings          │ u64 (8b)     │
│ total_licenses_sold     │ u64 (8b)     │
│ total_volume_lamports   │ u64 (8b)     │
│ bump                    │ u8 (1b)      │
├─────────────────────────┼──────────────┤
│ Total Size              │ 69 bytes     │
│ Rent Exemption          │ ~0.0002 SOL  │
└─────────────────────────┴──────────────┘
```

### Listing (Per Asset)
```
┌─────────────────────────┬──────────────┐
│ Field                   │ Type         │
├─────────────────────────┼──────────────┤
│ supplier                │ Pubkey (32b) │
│ asset_id                │ String (36b) │
│ name                    │ String (68b) │
│ industry                │ String (36b) │
│ price_lamports          │ u64 (8b)     │
│ record_count            │ u64 (8b)     │
│ data_hash               │ [u8;32](32b) │
│ is_active               │ bool (1b)    │
│ licenses_sold           │ u64 (8b)     │
│ total_revenue           │ u64 (8b)     │
│ created_at              │ i64 (8b)     │
│ bump                    │ u8 (1b)      │
├─────────────────────────┼──────────────┤
│ Total Size              │ ~254 bytes   │
│ Rent Exemption          │ ~0.0009 SOL  │
└─────────────────────────┴──────────────┘
```

### License (Per Purchase)
```
┌─────────────────────────┬──────────────┐
│ Field                   │ Type         │
├─────────────────────────┼──────────────┤
│ buyer                   │ Pubkey (32b) │
│ listing                 │ Pubkey (32b) │
│ supplier                │ Pubkey (32b) │
│ amount_paid             │ u64 (8b)     │
│ supplier_received       │ u64 (8b)     │
│ platform_received       │ u64 (8b)     │
│ partner_received        │ u64 (8b)     │
│ months                  │ u8 (1b)      │
│ purchased_at            │ i64 (8b)     │
│ expires_at              │ i64 (8b)     │
│ is_active               │ bool (1b)    │
│ bump                    │ u8 (1b)      │
├─────────────────────────┼──────────────┤
│ Total Size              │ ~157 bytes   │
│ Rent Exemption          │ ~0.0005 SOL  │
└─────────────────────────┴──────────────┘
```

---

## Security Architecture

### Access Control
| Action | Who Can Call | Enforcement |
|--------|-------------|-------------|
| Initialize exchange | BITS admin (once) | PDA seed constraint |
| List asset | Any supplier | Signer verification |
| Purchase license | Any buyer | Signer + payment verification |
| Deactivate listing | Original supplier only | `constraint = listing.supplier == supplier.key()` |
| Update fees | BITS admin only | `constraint = exchange.authority == authority.key()` |

### Program Derived Addresses (PDAs)
All accounts use deterministic PDAs — no one can create fake accounts:
- **Exchange:** `seeds = [b"exchange"]` — one global config
- **Listing:** `seeds = [b"listing", asset_id]` — one per asset
- **License:** `seeds = [b"license", buyer, listing]` — one per buyer per listing

### Math Safety
- All arithmetic uses checked operations (`checked_mul`, `checked_sub`, `checked_div`)
- Overflow returns a clean error instead of undefined behavior
- Fee cap enforced at 50% maximum (5000 basis points)

### Atomic Transactions
- License purchase is atomic: if ANY transfer fails, ALL transfers fail
- No partial payments — supplier, platform, and partner all get paid or none do
- Solana's native transaction model guarantees this

### Data Integrity
- Every listing includes a `data_hash` (SHA-256) of the complete data corpus
- This hash is permanently recorded on-chain
- Anyone can verify: hash the data → compare to on-chain hash
- Tamper-proof: if the data changes, the hash won't match

### Immutability
- Once deployed, the smart contract code cannot be changed
- Contract logic is publicly auditable by anyone
- Program ID serves as a permanent reference (like a patent number)

---

## Revenue Split Model

```
Buyer pays $1,000/month for 12-month license = $12,000 total

Smart contract automatically distributes:
┌──────────────────────────────────────────┐
│  BUYER pays $12,000                       │
│  ┌─────────────────────────────────────┐ │
│  │ 80% → SUPPLIER      = $9,600       │ │
│  │ 15% → BITS PLATFORM = $1,800       │ │
│  │  5% → DATA PARTNER  = $600         │ │
│  └─────────────────────────────────────┘ │
│  All transfers are ATOMIC (all or none)   │
│  Recorded permanently on Solana blockchain │
└──────────────────────────────────────────┘
```

### Fee Structure (Configurable)
| Recipient | Default | Range | Description |
|-----------|---------|-------|-------------|
| Supplier | 80% | 50-95% | Data owner — the small business |
| BITS Platform | 15% | 5-25% | TORA Exchange operating fee |
| Data Partner | 5% | 0-25% | Referral/integration partner |
| **Combined fees** | **20%** | **Max 50%** | Smart contract enforced cap |

---

## Transaction Flow

```
1. SUPPLIER calls list_asset()
   → Listing account created on-chain
   → Asset ID, price, data hash recorded permanently
   
2. BUYER calls purchase_license()
   → Smart contract calculates split
   → Transfer 1: Buyer → Supplier (80%)
   → Transfer 2: Buyer → BITS Treasury (15%)  
   → Transfer 3: Buyer → Partner Treasury (5%)
   → License account created on-chain
   → All 3 transfers are ATOMIC
   
3. VERIFICATION
   → Anyone can query the License account
   → Buyer can prove they hold a valid license
   → Supplier can verify revenue received
   → All data on Solana Explorer (public ledger)
```

---

## Deployment Costs

| Item | SOL | USD (~$128/SOL) |
|------|-----|-----------------|
| Program deploy (one-time) | ~1.89 SOL | ~$242 |
| Exchange config (one-time) | ~0.0002 SOL | ~$0.03 |
| Per listing | ~0.0009 SOL | ~$0.12 |
| Per license | ~0.0005 SOL | ~$0.06 |
| Transaction fee | ~0.000005 SOL | ~$0.0006 |

**One-time deployment: ~$242. Per-transaction cost: fractions of a penny.**

---

## Wallet Registry

| Product | Wallet Address | Network |
|---------|---------------|---------|
| DataVault | `9nqb1rUaP6UfXMCbA6mpwppBvPNmpRSiN4J2stG7yXSG` | Mainnet-Beta |
| TRACE | `EmktnJnnKc8DAxju62NAL2SM78QxvuiyVoN9JzeDFpjT` | Mainnet-Beta |
| TORA Exchange | `3u5SRdybcbkAuqZzPh4bDN1t8PZbHT6czzoKoY19KSC8` | Mainnet-Beta |

---

## Future Enhancements (Roadmap)

1. **USDC/SPL Token Support** — Accept USDC instead of SOL for license payments
2. **Zebec Streaming Integration** — Real-time continuous payment streaming
3. **Multi-signature Authority** — Require multiple signers for admin operations
4. **License Renewal** — Automatic renewal with on-chain subscription logic
5. **Dispute Resolution** — On-chain arbitration mechanism
6. **Cross-chain Bridge** — Accept payments from Ethereum, Polygon via Wormhole

---

## Patent Coverage

The TORA Exchange smart contract architecture is covered under the BITS DataVault Provisional Patent:
- **Claim 6 (Independent):** Data licensing marketplace system
- **Claim 14:** Liquidity event revenue distribution
- **Claim 15:** Aggregated dataset licensing via smart contract
- **FIG. 8:** Data licensing pipeline (aggregation → licensing → smart contract → automated revenue distribution)

The smart contract IS the implementation of Claim 6 — the automated revenue distribution described in the patent is exactly what `purchase_license()` does.

---

*Document maintained by B.I.T.S. — Blockchain Integrative Technology Solutions*
*Contact: contact@bitscorp.us | bitscorp.us*
*TORA Exchange: toraexchange.com*
