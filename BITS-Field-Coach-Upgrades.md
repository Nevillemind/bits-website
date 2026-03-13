# BITS Field Coach — Intelligence Upgrade Changelog
_Maintained by Doug Digital | Updated: February 27, 2026_

---

## v2.0 — February 27, 2026
### Phase 1: Semantic Search | Phase 2: Worker Memory | Phase 3: Vision Intelligence

This release transformed Field Coach from a smart SOP lookup into a workforce intelligence platform.

---

### 🔍 PHASE 1 — Semantic Search Engine

**Problem solved:** Old keyword matching returned whole files based on word overlap. Missed synonyms, returned irrelevant content, no understanding of meaning.

**What's new:**
- TF-IDF vector search scores every chunk individually (171 chunks across 9 files)
- Synonym expansion — "dig" finds excavation content; "GFCI" finds ground fault content
- Word stem normalization — excavating/excavation/excavated all match
- Cached to disk — instant startup after first index build
- Keyword fallback ensures zero downtime

**New knowledge sources:**
| File | Source | Content |
|------|--------|---------|
| `osha-1926-construction-safety.txt` | OSHA | All construction safety — fall protection, scaffolding, excavation, masonry, electrical, ladders |
| `osha-1910-general-industry.txt` | OSHA | LOTO, HazCom, respiratory protection |
| `virginia-building-code.txt` | Virginia DHCD | Residential + commercial code requirements |
| `epa-lead-asbestos-regulations.txt` | EPA/VADEQ | RRP rule, asbestos abatement |
| `trade-quick-reference.txt` | Industry standards | Masonry, electrical, plumbing, HVAC, fire protection quick reference |

**Files:** `app/semantic_search.py`, `scripts/scrape_trade_knowledge.py`

---

### 👷 PHASE 2 — Worker Profiles & Memory

**Problem solved:** Every worker got identical, impersonal responses. No history, no adaptation, no crew visibility.

**What's new:**
- PIN + name profiles (e.g., Sean Jones / PIN: 1467)
- Personalized greeting by name on login
- Response depth adapts to skill level:
  - **Rookie:** Step-by-step, explains "why," encouraging tone
  - **Journeyman:** Direct, professional, cites codes and specs
  - **Foreman:** Full picture — safety, crew management, liability implications
- Question history logged per worker
- Knowledge gap detection: asked 3x = flagged for training
- Foreman crew dashboard: all workers, activity, training priorities
- Client-side data storage — worker data lives on client's BITS box, never on BITS servers

**Trades supported (21):**
Mason, Electrician, Plumber, HVAC, Fire Protection, General Contractor, Roofer, Framer, Drywall, Painter, Welder, Concrete, Insulation, Flooring, Landscaping, Demo, Site Supervisor, Boiler Technician, Steamfitter, Pipefitter, Sprinkler Fitter

**API endpoints added:**
- `POST /worker/register`
- `POST /worker/login`
- `GET /worker/profile/{pin}`
- `POST /worker/update`
- `DELETE /worker/{pin}`
- `GET /crew/dashboard`
- `GET /crew/gaps`

**Files:** `app/worker_profiles.py`, `.worker_data/workers.json`

---

### 👁️ PHASE 3 — Trade-Specific Vision Intelligence

**Problem solved:** Camera vision was generic — "I see a wall." No trade expertise applied to image analysis.

**What's new:**
- 8 trade-specific expert vision prompts
- Auto-selects the right expert based on worker's trade profile (Phase 2 integration)
- Every image response includes specific code citations and actionable findings

**Vision expert prompts:**

| Trade | What Field Coach analyzes |
|-------|--------------------------|
| Mason | Mortar quality, crack types, coursing, OSHA 1926.706 |
| Electrician | NEC violations, wire gauges, clearances, torque specs |
| Plumber | Pipe slope, traps, venting, IPC compliance |
| HVAC | Refrigerant leak detection, equipment ID, EPA 608 |
| Fire Protection | NFPA 13/25 violations, sprinkler clearances, valve positions |
| Boiler Technician | ASME compliance, pressure readings, relief valve condition |
| Roofer | Flashing, shingle pattern, OSHA fall protection |
| General | Full PPE scan + OSHA violation detection with section numbers |

**Live demo result:**
- Question: "How do I change out an electrical panel?"
- Cited: NEC 110.2(A), 110.26(A), 110.14(D), 110.22, 250.62, 250.24(B); OSHA 1926.20, 1926.417, 1910.147; NFPA 70E 120.5; Virginia permit requirement
- Response time: **19.2 seconds**

**Files:** `app/main.py` — `VISION_PROMPTS` dict + `_build_vision_system_prompt()`

---

## v1.0 — Week of February 24, 2026
### Initial Launch

- FastAPI backend — model-agnostic (Gemini, OpenAI, Anthropic, Ollama)
- 5 manually written masonry SOPs
- Keyword search (word overlap matching)
- Voice Q&A + camera vision (basic)
- Login-protected UI
- MentraOS glasses integration (/glasses/ask, /glasses/vision, /glasses/escalate)
- Live at bitsfieldcoach.com via Cloudflare Tunnel
- Mac mini 192.168.1.209:8000

---

## v2.1 — February 28, 2026
### Phase 4: Supervisor Dashboard + Push Notifications + Ratings Engine

**The feedback loop is now closed.** Workers rate every answer. Bad answers get pushed to the supervisor. Supervisor corrects in one tap. Correction goes straight into the knowledge base. Every client's Field Coach gets smarter automatically — and that intelligence belongs to them.

---

### 📊 Supervisor Dashboard (`/supervisor`)

**What it is:** A password-protected PWA dashboard for owners and foremen — add to phone home screen, looks and feels like a native app. No App Store.

**Three sections:**
- **🔴 Live Escalations** — when a worker hits "get help," supervisor sees it instantly with full context. Can respond directly from the dashboard.
- **👷 Crew Status** — workers, questions today, flagged count. Full worker list with last active time.
- **📋 Review Queue** — thumbs-down answers. Supervisor types the correct answer → "Save to Knowledge Base" → done. One tap.
- **🧠 Knowledge Gaps** — topics asked repeatedly. Training priorities surfaced automatically.

**Access:** `bitsfieldcoach.com/supervisor` | Password: configured in `.env`

---

### 🔔 Push Notifications (Native — No Telegram, No Extra App)

- VAPID keys generated, `pywebpush` installed
- Service worker v2 handles push delivery
- Escalation push: urgent vibration, "View Now" action, requires interaction
- Flagged answer push: fires on every thumbs down
- Supervisor enables alerts in one tap from the dashboard banner
- Works on iOS (when added to home screen) and Android

---

### 👍👎 Worker Rating System

- Appears after every answer: "Helpful? 👍 👎"
- One tap, no friction — worker is back to work in seconds
- All ratings stored with full Q&A context, worker identity, trade, timestamp
- Thumbs down triggers immediate push to all supervisor devices
- Worker PIN now passed on every `/ask` call — full context on every interaction

---

### 🔄 Auto-Improving Knowledge Base

- Supervisor correction → saved to `app/knowledge/correction-[id].txt`
- Semantic search index auto-invalidates and rebuilds
- Next worker who asks a similar question gets the corrected answer
- Full audit trail: who asked, what AI said, what supervisor corrected, when

---

### New API Endpoints (Phase 4)
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/supervisor` | Serve supervisor dashboard |
| POST | `/supervisor/auth` | Verify supervisor password |
| GET | `/supervisor/escalations` | Active escalations |
| POST | `/supervisor/escalations/{id}/dismiss` | Dismiss escalation |
| POST | `/supervisor/respond` | Send response to worker |
| GET | `/supervisor/review-queue` | Flagged answers pending review |
| POST | `/supervisor/correct` | Save correction to knowledge base |
| GET | `/push/vapid-key` | VAPID public key for browser push |
| POST | `/push/subscribe` | Register supervisor device |
| POST | `/rating` | Worker submits rating |

**GitHub commit:** `feat: Phase 4 — Supervisor Dashboard, Push Notifications, Ratings Engine` | `910c8e9`

---

## Roadmap

| Phase | Feature | Status |
|-------|---------|--------|
| 1 | Semantic Search — TF-IDF vector search, 4x accuracy | ✅ Complete (Feb 27) |
| 2 | Worker Profiles & Memory — PIN login, skill levels, crew dashboard | ✅ Complete (Feb 27) |
| 3 | Trade-Specific Vision — 8 expert vision prompts, auto-selects by trade | ✅ Complete (Feb 27) |
| 4 | Feedback Loop — ratings, supervisor dashboard, push notifications, KB auto-update | ✅ Complete (Feb 28) |
| 5 | Ollama Local AI — works offline, no internet required, deployed on client BITS box | 🔲 Next (client deployment) |
| 6 | Job Documentation — auto-generate reports from job site photos | 💡 Future |
| 7 | Multi-client Deployment — each BITS box runs isolated client instance | 💡 Future |
