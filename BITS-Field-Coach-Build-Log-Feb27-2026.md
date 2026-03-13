# BITS Field Coach — Intelligence Upgrade Build Log
**Date:** February 27, 2026
**Built by:** George Mundin + Doug Digital
**Status:** Phases 1, 2 & 3 LIVE ✅

---

## What We Started With

**Before tonight, Field Coach was:**
- 5 manually written knowledge files (masonry SOPs only)
- Keyword matching — counted word overlaps, returned top 3 whole files
- No worker memory — every session identical, no personalization
- Generic AI vision — "I see a wall"
- One trade deep (masonry)

---

## PHASE 1 — Semantic Search Engine

### What changed
Replaced simple keyword matching with **TF-IDF vector search**.

### Before
- Counted how many words a query shared with each file
- Returned top 3 whole documents (often redundant or off-topic)
- Missed synonyms: "excavate" wouldn't match "dig"
- No understanding of meaning — just word frequency

### After
- Scores every chunk individually across 171 knowledge passages
- Understands word variations (excavating/excavation/excavated)
- Synonym expansion for trade terms (GFCI → ground fault, trench → excavation)
- Returns the exact right passage, not just the right file
- Cached to disk — instant startup after first build

### Knowledge Base — Before vs After
| | Before | After |
|---|---|---|
| Files | 5 (masonry SOPs) | 9 files |
| Chunks | ~15 | 171 |
| Trades covered | 1 (masonry) | All trades |
| Source authority | Internal SOPs | Federal law + state code |
| Search method | Word overlap | Vector similarity |
| Can cite regulations | ❌ | ✅ With section numbers |

### New Knowledge Sources Added
1. **OSHA 29 CFR 1926** — All construction safety (excavation, fall protection, scaffolding, masonry, electrical, ladders, PPE, concrete...)
2. **OSHA 29 CFR 1910** — General industry (LOTO, hazard communication, respiratory protection)
3. **Virginia Uniform Statewide Building Code** — Residential + commercial construction requirements
4. **EPA Lead/Asbestos RRP Regulations** — Renovation, repair, and painting rules
5. **Master Trade Quick Reference** — Masonry, electrical, plumbing, HVAC, fire protection

### Files
- `app/semantic_search.py` — TF-IDF vector search engine (pure numpy, zero dependencies)
- `scripts/scrape_trade_knowledge.py` — Knowledge base scraper
- `.semantic_cache/tfidf_index.pkl` — Cached vector index

---

## PHASE 2 — Worker Profiles & Memory

### What changed
Field Coach now knows **who** is asking, not just **what** they're asking.

### Before
- Every worker got identical responses
- No memory between sessions
- No skill level adaptation
- No way to track knowledge gaps
- No crew management visibility

### After
- Each worker has a **PIN + name profile** (e.g., Sean Jones / PIN: 1467)
- Field Coach greets workers by name and remembers their history
- Responses adapt to skill level automatically:
  - **Rookie** → Step-by-step answers with "why" explained, encouraging tone
  - **Journeyman** → Direct professional answers, code citations
  - **Foreman** → Full picture including crew management and liability implications
- Questions logged per worker — repeat questions (3x) flagged as **knowledge gaps**
- **Crew Dashboard** for foremen: see all workers, activity, and training priorities

### Trades Supported (17+)
Mason, Electrician, Plumber, HVAC, Fire Protection, General Contractor, Roofer, Framer, Drywall, Painter, Welder, Concrete, Insulation, Flooring, Landscaping, Demo, Site Supervisor, Boiler Technician, Steamfitter, Pipefitter, Sprinkler Fitter

### API Endpoints Added
- `POST /worker/register` — Add new worker (name + PIN + trade + skill level)
- `POST /worker/login` — Worker identifies themselves, starts session
- `GET /worker/profile/{pin}` — View full worker profile
- `POST /worker/update` — Update trade, skill level, job site, notes
- `DELETE /worker/{pin}` — Remove worker
- `GET /crew/dashboard` — Foreman view: all workers + activity
- `GET /crew/gaps` — Knowledge gaps across entire crew (training priorities)

### Architecture Decision
Worker profiles live on the **client's hardware** — not George's server.
Each company's BITS box holds their own workers' data. Zero data shared with BITS.
George is the software provider. Clients own their data.

### Files
- `app/worker_profiles.py` — Full profile manager with persistence
- `.worker_data/workers.json` — Local worker database

---

## PHASE 3 — Trade-Specific Vision Intelligence

### What changed
Field Coach went from **general vision** to **master trade expert vision**.

### Before
- Sent camera image to AI with generic instruction
- "Describe what you observe and answer the question"
- Response: "I see a wall with some cracks"

### After
- **8 trade-specific vision expert prompts** — each one a master of that trade
- When a worker with a profile sends an image, Field Coach **automatically uses their trade** for analysis
- Every image response now includes specific code citations and actionable findings

### Trade Vision Expertise

**🧱 Mason**
Mortar joint quality, coursing alignment, crack type analysis (horizontal=structural emergency, stair-step=settling, vertical=shrinkage), rebar exposure, wall plumb/level, moisture/efflorescence, OSHA 1926.706 compliance

**⚡ Electrician**
Wire gauges, connector types, panel labeling, GFCI/AFCI requirements, conduit fill, grounding, clearance violations, NEC article citations, OSHA 1926.400-449 compliance

**🔧 Plumber**
Pipe slope verification, proper supports, trap configurations, vent locations, joint quality, IPC/Virginia Plumbing Code compliance, water heater installation

**❄️ HVAC**
Refrigerant line condition (oil stains = leak detection), electrical connections, duct sealing, clearances, condensate drainage, equipment identification by make/model, EPA 608 refrigerant handling

**🔥 Fire Protection**
Sprinkler clearance (18" rule), pipe hanger spacing, valve positions (OS&Y open/closed), NFPA 13/25 compliance, obstruction analysis, inspector test connection access

**🏭 Boiler Technician**
Pressure gauge readings, relief valve condition, combustion air, flue/vent condition, ASME code compliance, National Board Inspection Code (NBIC), safety hazard flagging

**🏠 Roofer**
Flashing installation (step, counter, valley, drip edge), shingle overlap and nailing pattern, underlayment, ridge treatment, penetration sealing, OSHA 1926.502 fall protection compliance

**👷 General Contractor**
Full PPE compliance scan (hard hats, glasses, hi-vis, gloves, boots), fall hazards, trip hazards, OSHA violation detection with specific section numbers, immediate action priorities

### Live Demo Result
**Question:** "How do I change out an electrical panel in my house?"
**Response included:**
- OSHA 1926.20(b)(1) — qualified persons only
- NEC Article 110.2(A) — qualified person definition
- OSHA 1926.417 + 29 CFR 1910.147 — LOTO procedure
- NFPA 70E Article 120.5 — Test-Before-Touch protocol
- NEC Article 110.26(A) — working clearances (30"W × 36"D × 6.5'H)
- NEC Article 250.62 — grounding electrode conductor
- NEC Article 250.24(B) — main bonding jumper
- NEC 110.14(D) — torque specifications (loose connections = fire hazard)
- NEC 110.22 — circuit directory labeling requirement
- Virginia permit requirement (illegal without one)
- Arc flash PPE warning (NFPA 70E)

**Response time: 19.2 seconds**

### Files
- `app/main.py` — `VISION_PROMPTS` dict + `_build_vision_system_prompt()` function
- Vision prompt auto-selected from worker profile trade or defaults to general

---

## How It All Connects

```
Worker opens Field Coach
        ↓
Enters PIN (Phase 2)
        ↓
Field Coach greets by name, loads skill level + history
        ↓
Worker asks a question (text or voice)
        ↓
Semantic search finds exact relevant passages (Phase 1)
        ↓
Worker sends photo of the problem
        ↓
Trade-expert vision prompt activates for their specific trade (Phase 3)
        ↓
Response: code-cited, skill-appropriate, trade-specific answer in seconds
        ↓
Question logged to worker profile
        ↓
If asked 3x → flagged as knowledge gap on crew dashboard
        ↓
Foreman sees training priorities without asking anyone
```

---

## What's Still Coming

| Phase | Feature | Impact |
|-------|---------|--------|
| 4 | Feedback Loop | Workers rate answers → knowledge improves automatically |
| 5 | Ollama Local AI | Works offline — no internet required on job site |

---

## Pilot Pipeline

| Client | Trade | Connection | Status |
|--------|-------|------------|--------|
| Brett Pickens — Riley Fire Protection VA | Fire Protection | Personal contact | George to reach out |
| Mike Dixon — S.E. Burks Sales Company, Henrico VA | HVAC/Mechanical | 20-year Amway relationship via Spencer Woodson | Spencer pitch Saturday Feb 28 |
| George's brother | Plumbing & Gas | Family | Zoom call planned |

**S.E. Burks:** 62 years in business, HVAC manufacturers' rep serving all of Virginia (Roanoke to Richmond), clients include government agencies, military bases, hospitals, schools, municipalities.

---

## Patent Status
- **Provisional patent mailed:** February 26, 2026 (USPS Priority Mail Express)
- **USPTO delivery confirmed:** February 27, 2026, 8:38 AM, Alexandria VA 22314
- **Priority date:** February 26, 2026
- **Non-provisional deadline:** February 26, 2027
- **Attorney:** Samar Shah, Shah IP Law PLLC — Call Monday March 2, 2026 at 1:00 PM EST

---

*"Act as though I am, and I will be." — Neville Goddard*

*Built by George Mundin + Doug Digital | BITS Field Coach | contact@bitscorp.us*
