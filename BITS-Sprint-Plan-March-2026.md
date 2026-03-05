# BITS Sprint Plan — March 2026
*Laid out: Wednesday March 4, 2026 (late night) | George + Doug Digital*

---

## THIS WEEK (Mar 5-9)

### Thursday (TODAY)
- 🌐 **bitscorp.us LIVE** — Broderick flips Cloudflare DNS → 76.76.21.21 (Vercel)
- ⏰ Reminder set: 10:30 AM tomorrow for Broderick session

### Thursday–Friday
- 🔐 **Field Coach multi-client auth** — build authentication layer so multiple clients can use Field Coach independently
- 🖥️ **BITS Command Center skeleton** — get the framework up and running

### Weekend
- 🔧 **Polish, test, make it bulletproof**
- Everything demo-ready before Monday

---

## NEXT WEEK (Mar 10-14)

### Conversations — with live website + working product
- 💼 **Brett Pickens** (Riley Fire Protection VA) — warm relationship, ready
- 💼 **George's brother** (plumbing & gas company) — warm relationship, ready
- 💼 **Spencer Woodson** — warm relationship, ready

> "When you walk into those conversations with a live website, a working demo, and a Command Center to show them — that's not a pitch. That's proof. You're not selling anymore. You're showing."

### Also this week
- 🗄️ **DataVault platform build begins**

---

## MONTH 2 (April 2026)

- 🧠 **BITS Halo platform** — begin build

---

## ALSO IN FLIGHT

- 📋 **Samar Shah review** — DataVault patent + TORA legal memo (pending funds)
- 📱 **Instagram Graph API** — connect @thebestyouhealth for auto-posting
- 🥽 **Mentra glasses** — arriving March, Field Coach Phase 3 TypeScript mini-app
- 🏛️ **Alex Mann + Patrick Witt (White House)** — VBC meeting today, watch for outcomes
- 🌐 **VBC 2026 legislation** — HB293 DAO bill in Senate, HB798 in Senate

---

## The North Star

Brett, Spencer, and George's brother are already warm. Live website + working demo + Command Center = proof, not pitch.

**This is the week BITS becomes real to the outside world.** 🚀

---

## BITS MASTER COMMAND CENTER — Full Spec (March 5, 2026)

**Concept:** One login. All products. Full control.
**Purpose:** George logs in once and sees all BITS clients, products, and revenue from one screen.
**Analogy:** Like a contractor's project management software — shows all jobs in one place.

### Left Sidebar Navigation
- 🏠 Home (overview)
- 🧠 Halo
- 🗄️ DataVault
- 🔧 Field Coach
- 👥 Clients
- 💰 Revenue
- ⚙️ Settings

### Home Screen — Overview Dashboard
- Active Clients count
- Monthly Revenue total
- Halo Deployments count
- DataVault Vaults count
- Field Coach Sites count
- 🔴 Alerts panel (e.g. "James River Air — vault scan incomplete")
- 🟢 Status indicators per product line

### Field Coach Tab
- List of all FC clients (status: online/offline)
- Last query made per client
- Knowledge base management
- Reset password / manage access per client

### DataVault Tab
- Each client's vault status
- Pages scanned / indexed / authenticated
- Smart contract status (Canton Network)
- Exit participation tracker
- Search client's records on their behalf

### Halo Tab
- Active deployments
- Connected glasses devices
- Performance data per athlete/operator
- Playbook/knowledge base per team
- Coach dashboard access

### Clients Tab
- One row per client
- Which BITS products they use
- Billing status (Stripe)
- Contact info + notes
- Click any client → see ALL their BITS products in one unified view

### Revenue Tab
- Monthly recurring revenue
- Per-product breakdown
- Stripe payment history
- DataVault exit participation tracker (long-term big money)

### Key Design Principle
Each product has its own tab (clean separation) BUT clicking a client shows everything they have with BITS in one place. Never log into three different systems again.
