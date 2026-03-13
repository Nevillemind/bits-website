# BITS Box — Client Setup Guide
## A-to-Z Deployment Checklist
_Version 1.0 | Updated: Feb 28, 2026_
_For use by George Mundin / BITS field deployment_

---

## OVERVIEW

This guide walks you through everything from unboxing a Mac Mini to handing a fully configured BITS AI Box to a client. Follow every step in order. Check each box as you go.

**Total estimated setup time:** 3–5 hours (includes interview + knowledge build)
**Hardware required:** M4 Mac Mini ($599)
**Software:** All free

---

## PHASE 1 — HARDWARE SETUP
_Time: ~30 minutes_

- [ ] **Unbox** the Mac Mini M4
- [ ] **Connect** to power, monitor, keyboard, and mouse (just for initial setup — client may not need a monitor after this)
- [ ] **Power on** — hold the power button until you see the startup screen
- [ ] **Complete macOS Setup Assistant:**
  - Language: English
  - Region: United States
  - Skip "Transfer from another Mac" → choose "Don't transfer any information"
  - Sign in with Apple ID → **Skip this** (use a BITS Apple ID or skip entirely)
  - Computer Name: Set to client's business name, e.g. `BITS-RichmondPlumbing`
  - Create admin account: Username `bitsadmin` / Password: [use your BITS standard password]
  - Skip iCloud, Screen Time, Siri if possible
- [ ] **Connect to internet** (client's WiFi or ethernet)
- [ ] **Run macOS Software Update** → System Settings → General → Software Update → install all updates
- [ ] **Restart** if updates were installed

---

## PHASE 2 — INSTALL OLLAMA (The AI Engine)
_Time: ~20 minutes + model download time_

Ollama is the free, open-source software that runs AI models locally on the Mac Mini. No subscriptions. No internet required after setup.

- [ ] Open **Safari** → go to **ollama.com**
- [ ] Click **Download for Mac** → open the downloaded file → drag Ollama to Applications
- [ ] Launch Ollama from Applications folder (it will run in the menu bar — look for the llama icon)
- [ ] Open **Terminal** (Spotlight → type "Terminal" → Enter)
- [ ] **Pull the AI model** — type this command and press Enter:
  ```
  ollama pull qwen2.5:72b
  ```
  _(This downloads the AI model — about 40GB. On fast internet: 20-40 min. On slow: could be 1-2 hours. Let it run.)_

  > **Alternative for lighter/faster setup:** `ollama pull llama3.1:8b` (smaller, less capable but faster responses)

- [ ] When download completes, **test Ollama** is working:
  ```
  ollama run qwen2.5:72b "Hello, are you working?"
  ```
  You should get a response. If you do — Ollama is live. Type `/bye` to exit.

- [ ] **Set Ollama to start on login:**
  - Ollama does this automatically if you launched it from Applications.
  - Confirm: System Settings → General → Login Items → Ollama should be listed

---

## PHASE 3 — INSTALL FIELD COACH
_Time: ~30 minutes_

Field Coach is BITS's AI assistant platform. It connects to Ollama and gives field workers an easy interface to ask questions, upload photos, and get job-site answers.

- [ ] Open Terminal
- [ ] **Install Homebrew** (Mac package manager — needed for Python):
  ```
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  ```
  Follow the prompts. May ask for your admin password.

- [ ] **Install Python 3:**
  ```
  brew install python
  ```

- [ ] **Clone the Field Coach code from GitHub:**
  ```
  cd ~/Desktop
  git clone https://github.com/Nevillemind/BITS-FieldCoach.git
  cd BITS-FieldCoach
  ```

- [ ] **Install Python dependencies:**
  ```
  pip3 install -r requirements.txt
  ```

- [ ] **Create the environment file (.env):**
  ```
  cp .env.example .env
  nano .env
  ```
  Fill in the following:
  ```
  OPENAI_API_KEY=your_key_here         # Only needed if using GPT-4o fallback
  OLLAMA_BASE_URL=http://localhost:11434
  OLLAMA_MODEL=qwen2.5:72b
  USE_LOCAL_AI=true
  ```
  Save: press `Ctrl+X` → `Y` → Enter

- [ ] **Add client's knowledge base** (see Phase 4 below — do the interview first, then come back here)

- [ ] **Start Field Coach:**
  ```
  bash start.sh
  ```
  You should see: `Uvicorn running on http://0.0.0.0:8000`

- [ ] **Test in browser:** Open Safari → go to `http://localhost:8000`
  - You should see the Field Coach worker interface
  - Test supervisor dashboard: `http://localhost:8000/supervisor`
  - Supervisor password: `BITSsupervisor2026!` (or set a client-specific password)

- [ ] **Set Field Coach to auto-start on reboot:**
  ```
  nano ~/Library/LaunchAgents/com.bits.fieldcoach.plist
  ```
  Paste this:
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0">
  <dict>
    <key>Label</key>
    <string>com.bits.fieldcoach</string>
    <key>ProgramArguments</key>
    <array>
      <string>/bin/bash</string>
      <string>/Users/bitsadmin/Desktop/BITS-FieldCoach/start.sh</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>WorkingDirectory</key>
    <string>/Users/bitsadmin/Desktop/BITS-FieldCoach</string>
  </dict>
  </plist>
  ```
  Save → then run:
  ```
  launchctl load ~/Library/LaunchAgents/com.bits.fieldcoach.plist
  ```

---

## PHASE 4 — CLIENT KNOWLEDGE EXTRACTION INTERVIEW
_Time: 60–90 minutes | Done via Zoom or in person_
_Complete this BEFORE loading knowledge base into Field Coach_

This is where you build the client's AI brain. You're turning their tribal knowledge — the stuff workers know but never wrote down — into structured SOPs the AI can use.

### BEFORE THE INTERVIEW
- [ ] Book 60-90 min with the business owner (Zoom works great)
- [ ] Record the session (use Grain.ai, Zoom built-in, or any recorder)
- [ ] Have this form open and take notes as you go

---

### SECTION A — BUSINESS OVERVIEW

Ask the owner:

1. "Tell me what your business does — trade, services, typical jobs."
   → _Notes:_

2. "How many workers do you have? What are their roles?"
   → _Notes:_

3. "What's your biggest operational headache right now?"
   → _Notes:_

4. "What do new employees struggle with most in their first 30-60 days?"
   → _Notes:_

5. "What are your most common job types — the ones you do week in, week out?"
   → _Notes:_

---

### SECTION B — SAFETY & COMPLIANCE

6. "What are your top safety rules on a job site?"
   → _Notes:_

7. "What PPE do workers need to wear? When?"
   → _Notes:_

8. "Have you had any incidents or near-misses? What happened and what's the lesson?"
   → _Notes:_

9. "Any state or local codes your team needs to know cold?"
   → _Notes:_

---

### SECTION C — JOB PROCESSES & SOPs

10. "Walk me through how your team starts a job from arrival to first task."
    → _Notes:_

11. "What are the most common mistakes workers make — the ones that cost you money or time?"
    → _Notes:_

12. "What questions do workers ask you most often?"
    → _Notes:_

13. "What does a perfect job look like? Walk me through it start to finish."
    → _Notes:_

14. "What are your quality standards — how do you know a job is done right?"
    → _Notes:_

---

### SECTION D — TOOLS & EQUIPMENT

15. "What are your main tools and equipment? Any specific models?"
    → _Notes:_

16. "How should tools be maintained? Any daily or weekly checks?"
    → _Notes:_

17. "What do workers do when equipment breaks on site?"
    → _Notes:_

---

### SECTION E — CLIENT INTERACTION

18. "How do your workers communicate with customers on site?"
    → _Notes:_

19. "What should workers never say or do in front of a client?"
    → _Notes:_

20. "How do you handle complaints or callbacks?"
    → _Notes:_

---

### SECTION F — WORKER PROFILES

Fill this out for each worker who will use Field Coach:

| Name | Role/Title | Trade Specialty | Experience Level | Primary Language |
|------|-----------|-----------------|-----------------|-----------------|
|      |           |                 |                 |                 |
|      |           |                 |                 |                 |
|      |           |                 |                 |                 |

---

### AFTER THE INTERVIEW
- [ ] Export the Zoom/Grain recording transcript (or send the audio recording)
- [ ] Send transcript/recording to Doug Digital — he converts it into structured .txt SOP files
- [ ] Files get named by topic, e.g.: `safety-ppe.txt`, `new-job-startup.txt`, `tool-maintenance.txt`, `client-communication.txt`

> **Note:** George conducts this interview personally. It builds trust, allows follow-up questions in real time, and gives George the feel of the business. Doug handles the transcript → SOP conversion after.

---

## PHASE 5 — LOAD KNOWLEDGE BASE INTO FIELD COACH
_Time: ~30 minutes_

- [ ] Place all client .txt SOP files into:
  ```
  ~/Desktop/BITS-FieldCoach/app/knowledge/
  ```
- [ ] Restart Field Coach:
  ```
  bash ~/Desktop/BITS-FieldCoach/start.sh
  ```
- [ ] **Test queries** using the worker interface (`http://localhost:8000`):
  - [ ] Ask a safety question → does it return a relevant answer?
  - [ ] Ask a process question → does it reference their SOP?
  - [ ] Ask something NOT in the knowledge base → does it handle it gracefully?
- [ ] **Add worker profiles** via supervisor dashboard (`http://localhost:8000/supervisor`)
  - Add each worker's name, role, trade, experience level

---

## PHASE 6 — REMOTE ACCESS SETUP (Parsec)
_Time: ~20 minutes_
_This lets you support the client remotely without being on-site_

- [ ] Go to **parsec.app** → create a free Parsec account (or use your BITS Parsec account)
- [ ] Download and install Parsec on the Mac Mini
- [ ] Log in and enable **hosting** so you can connect remotely
- [ ] Test from your own machine: connect to the client's Mac Mini via Parsec
- [ ] Confirm you can see the screen and control the machine
- [ ] Save the machine name/ID to your BITS client records

---

## PHASE 7 — FINAL TESTING & QA
_Time: ~30 minutes_

- [ ] **Reboot the Mac Mini** → confirm everything starts automatically:
  - Ollama running in menu bar ✓
  - Field Coach accessible at `http://localhost:8000` ✓
- [ ] **Test all Field Coach features:**
  - [ ] Voice input works
  - [ ] Camera/photo upload works (Trade Vision)
  - [ ] Supervisor dashboard loads ✓
  - [ ] Push notifications test ✓
  - [ ] At least 5 realistic worker queries answered correctly ✓
- [ ] **Stress test:** Ask 10 questions back to back — response time under 3 seconds?
- [ ] **Document any issues** and fix before handover

---

## PHASE 8 — CLIENT HANDOVER
_Time: ~45 minutes (in-person or Zoom walkthrough)_

### What to bring / prepare:
- [ ] **BITS Client Welcome Card** — printed or digital:
  - Worker URL: `http://[mac-mini-local-ip]:8000`
  - Supervisor URL: `http://[mac-mini-local-ip]:8000/supervisor`
  - Supervisor password: [set client-specific password]
  - BITS support: contact@bitscorp.us
  - George's phone/Telegram for urgent issues

- [ ] **Walkthrough with owner:**
  - [ ] Show the worker interface — have them ask a question
  - [ ] Show the supervisor dashboard — ratings, logs, push notifications
  - [ ] Show how to add/update knowledge (or explain that BITS handles this as part of subscription)
  - [ ] Explain what happens if internet goes down (Ollama still works locally — that's the point)
  - [ ] Show how to restart Field Coach if needed (simple script)

- [ ] **Have owner/manager sign:**
  - [ ] Pilot Agreement (or full Service Agreement)
  - [ ] NDA (if not already signed)

- [ ] **Collect first payment** (or confirm billing setup)

- [ ] **Set 30-day check-in** on calendar → review with client: What's working? What questions aren't being answered? Update knowledge base.

---

## QUICK REFERENCE — KEY COMMANDS

```bash
# Start Ollama model manually
ollama run qwen2.5:72b

# Start Field Coach
cd ~/Desktop/BITS-FieldCoach && bash start.sh

# Check Field Coach is running
curl http://localhost:8000/health

# Pull a new/updated model
ollama pull qwen2.5:72b

# See Ollama running models
ollama list
```

---

## CLIENT RECORD (fill out for each deployment)

| Field | Value |
|-------|-------|
| Client Name | |
| Business Name | |
| Trade/Industry | |
| Mac Mini Serial # | |
| IP Address (local) | |
| Parsec Machine ID | |
| Setup Date | |
| Supervisor Password | |
| Model Installed | |
| # of Workers | |
| Pilot Agreement Signed | Y / N |
| NDA Signed | Y / N |
| First Payment Date | |
| 30-Day Check-In Date | |

---

_Document maintained by Doug Digital | BITS internal use only_
_contact@bitscorp.us | bitscorp.us_
