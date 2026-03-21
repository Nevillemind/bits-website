# BITS Wallet Rotation — March 21, 2026

**Reason:** Security hardening — old wallet private keys were stored as plaintext JSON files on disk. New wallets generated and secured in macOS Keychain.

---

## New Wallet Addresses (ACTIVE)

| Product | New Address | Balance |
|---------|-------------|---------|
| TRACE | `6F61UJWh3ZE3viokmbhrcZvEie5p7MBwACWXmYoEcH2W` | 0.014 SOL |
| TRACE Shield | `FzNNw25jJA2M88qkpSTfvYKC3nzCXHppcouEW9BojGUt` | 0 SOL |
| DataVault | `Ff6knR5evTR9pv5arx3hA5R8Xk1jwi4Ujm2kJZ5Ef6AK` | 0.015 SOL |
| TORA Exchange | `9rkG2UMFZJE2a3LGWDD48Pypp1Y8yegTYzWamaTmfp2P` | 0.001 SOL |

## Old Wallet Addresses (ABANDONED — do not fund)

| Product | Old Address | Remaining |
|---------|-------------|-----------|
| TRACE | `EmktnJnnKc8DAxju62NAL2SM78QxvuiyVoN9JzeDFpjT` | ~0.001 SOL (dust) |
| TRACE Shield | `92YzBWxhvtuSYhjeUTm79RtaAaBYTeaCduYCLxW7Un5V` | 0 SOL |
| DataVault | `9nqb1rUaP6UfXMCbA6mpwppBvPNmpRSiN4J2stG7yXSG` | ~0.001 SOL (dust) |
| TORA Exchange | `3u5SRdybcbkAuqZzPh4bDN1t8PZbHT6czzoKoY19KSC8` | ~0.001 SOL (dust) |

*Dust remaining in old wallets is below minimum transfer threshold (~0.001 SOL). Old wallets are permanently abandoned.*

---

## Transfer Transaction Signatures

| Transfer | Signature |
|---------|-----------|
| TRACE old → new | `2dByo2cuvs1o9Kj3mAEvBeDLJojYwAACN4F9Q8yEvKcRfXFQE1f85RNYuSw5u5HikUZkS46X27x4v7VeyTHUNHL6` |
| DataVault old → new | `3bzTh5khWKE9MnZjsyhLfjhkJa6tF7JULXTkZ4gCPEYjCc59tSui8iLCARxN36AqpEPoTYmdQ1QoESedhNJRDrNT` |
| TORA old → new | `3xHEHnGLVnmgJk1wWiHBoedWojdL1y4hUYNbHVMQmnt2BRDw4MUA4SJpymMmQ6mqWUeHpNfRq2nA7Ljg758F1b9w` |

---

## Key Storage

Private keys are stored in macOS Keychain under these service names:
- `bits-trace-wallet` → account: `bits`
- `bits-shield-wallet` → account: `bits`
- `bits-datavault-wallet` → account: `bits`
- `bits-tora-wallet` → account: `bits`

**To retrieve a key (for deployment/config):**
```bash
security find-generic-password -s "bits-trace-wallet" -a "bits" -w
```

**Wallet JSON files in each product directory contain the NEW keys** and are protected by `.gitignore`.

---

## What Was Also Done (Same Session)

- Added `.gitignore` to TORA Exchange (was missing — wallet file was unprotected)
- Added `.gitignore` to DataVault (was missing — wallet file was unprotected)
- TRACE + Shield already had `.gitignore` protecting wallet files

---

## Next Steps

- [ ] When Swarm Network **Vault** launches (Yannick Myson / swarmnetwork.ai), migrate all secrets to that system
- [ ] Add `solana-keygen verify` to CI/CD to confirm wallet addresses match expected pubkeys
- [ ] Consider hardware wallet (Ledger) for any wallets holding significant value in future
