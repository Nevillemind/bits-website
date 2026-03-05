# BITS Command Center — Architecture Document
_Blueprint v1.0 | March 5, 2026 | George Mundin + Doug Digital_
_"Do not build anything without this document being right first."_

---

## The Core Principle

**One dashboard. Three separate databases. Zero data commingling.**

George logs into one interface and can see and manage all three BITS product lines. But each product line's data lives in its own completely isolated database. In an audit, legal proceeding, or acquisition — each product's records can be pulled independently without touching the others.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│           BITS COMMAND CENTER (George's Login)           │
│                    bitscorp.us/admin                     │
│                                                          │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────┐    │
│  │  FIELD   │  │  DATAVAULT   │  │      HALO      │    │
│  │  COACH   │  │     TAB      │  │      TAB       │    │
│  │   TAB    │  │              │  │                │    │
│  └────┬─────┘  └──────┬───────┘  └───────┬────────┘    │
│       │               │                  │              │
└───────┼───────────────┼──────────────────┼──────────────┘
        │               │                  │
        ▼               ▼                  ▼
┌──────────────┐ ┌─────────────┐ ┌────────────────┐
│ FIELD COACH  │ │  DATAVAULT  │ │  HALO DATABASE │
│   DATABASE   │ │  DATABASE   │ │   (future)     │
│              │ │             │ │                │
│ clients.json │ │ vaults.json │ │ teams.json     │
│ knowledge/   │ │ documents/  │ │ playbooks/     │
│ .worker_data/│ │ smart_cont/ │ │ devices/       │
└──────────────┘ └─────────────┘ └────────────────┘
   SEPARATE          SEPARATE         SEPARATE
   DATABASE          DATABASE         DATABASE
```

---

## Database Separation — How It Works

Each product line has its own:
- **Data directory** — files never shared across products
- **Client registry** — a client in Field Coach is NOT automatically in DataVault
- **Authentication** — separate logins per product for end users (not George)
- **Audit trail** — activity logs are product-specific

**A client can use one, two, or all three BITS products.** But their data in each product is stored separately. The Command Center is the only place where George sees them together — and only because he's the owner.

---

## The Five Tabs — What Each Connects To

### 🏠 Home Tab
- Reads summary counts from all three product databases
- Displays alerts from each product line
- No data modification — read-only overview

### 🔧 Field Coach Tab
**Reads/writes from:** `field-coach/` data directory
- `app/data/clients.json` — client registry
- `knowledge/{client_id}/` — each client's SOPs
- `.worker_data/{client_id}/` — each client's worker profiles
- **Field Coach API:** `http://localhost:8000` (already running)

### 🗄️ DataVault Tab
**Reads/writes from:** `datavault/` data directory (to be built — Step 4)
- `data/vaults.json` — client vault registry
- `data/documents/{client_id}/` — indexed document records
- `data/smart_contracts/{client_id}/` — Canton Network contract status
- **DataVault API:** `http://localhost:8001` (to be built)

### 🧠 Halo Tab
**Reads/writes from:** `halo/` data directory (to be built — Step 5)
- `data/teams.json` — team registry
- `data/playbooks/{team_id}/` — play knowledge bases
- `data/devices/{team_id}/` — connected glasses devices
- **Halo API:** `http://localhost:8002` (to be built)

### 👥 Clients Tab
- George manually manages this: one row per client
- Stores which BITS products each client uses
- Stores billing status, contact info, notes
- Clicking a client → pulls live data from whichever product DBs that client is in
- **Client master registry:** `command-center/data/master_clients.json`

### 💰 Revenue Tab
- Reads Stripe API (when integrated — Option 3)
- Per-product revenue breakdown pulled from each product's billing records
- DataVault exit participation tracker (separate ledger)

### ⚙️ Settings Tab
- Command Center admin settings
- Add/remove clients per product
- Manage George's master password

---

## Audit Isolation — How It Holds Up

If BITS is ever audited, acquired, or a product is spun off:

| Scenario | What happens |
|---|---|
| Field Coach audit | Pull only `field-coach/` directory — zero DataVault or Halo data present |
| DataVault legal review | Pull only `datavault/` directory — completely separate |
| Field Coach acquisition | Transfer `field-coach/` codebase and data — Halo/DataVault not included |
| Tax/revenue audit | Revenue tab shows per-product breakdown — clean separation |

The data never mingles. The Command Center is a **viewer** — it reads from each database but doesn't merge them.

---

## What Goes to GitHub vs Local Files

### GitHub (BITS-Corp organization)
| Repo | What lives there |
|---|---|
| `BITS-Corp/BITS-FieldCoach` | Field Coach codebase — already live |
| `BITS-Corp/BITS-CommandCenter` | Command Center codebase — to be created |
| `BITS-Corp/BITS-DataVault` | DataVault codebase — to be created |
| `BITS-Corp/BITS-Halo` | Halo codebase — to be created |

**Rule:** Code goes to GitHub. Client data NEVER goes to GitHub.

### Local Files Only (Mac mini — never pushed)
- `app/data/clients.json` — real client credentials
- `knowledge/{client_id}/` — client SOPs (proprietary)
- `.worker_data/{client_id}/` — worker profiles
- `.env` files — API keys and secrets
- Any document with client names, passwords, or business data

### Documentation (this workspace — `~/.openclaw/workspace/BITS/`)
- Architecture docs like this one
- Sprint plans, build sequences
- Legal documents, patents, trademarks
- Sales scripts, strategy docs

---

## Build Sequence (Reference)

| Step | What | Status |
|---|---|---|
| 1 | bitscorp.us live | ⏳ Waiting on Broderick DNS |
| 2 | Field Coach multi-client auth | ✅ Built March 5, 2026 |
| 3 | BITS Command Center | 🔜 Next — after website live |
| 4 | DataVault Platform | 🔜 Weeks 3-5 |
| 5 | Halo Platform | 🔜 Month 2 |

---

## Before Building the Command Center

These questions must be answered:
- [ ] Where does it live? bitscorp.us/admin? Or separate domain?
- [ ] What framework? (Next.js recommended — already used in Mission Control)
- [ ] Does it run on the Mac mini locally, or deployed to Vercel?
- [ ] Stripe integration in v1 or added later (Option 3)?

---

_This document is the blueprint. No code gets written until this is reviewed and approved by George._
_Last updated: March 5, 2026_
