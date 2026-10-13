# Vee Market fake server — context for Claude

## Project
Vee Market: paddy marketplace (Team ApisGrid, Univ. of Ruhuna). A pole-shaped ESP32 moisture
device (3 capacitive sensor zones top/middle/bottom + temperature probe, screen, one button,
buzzer) measures paddy moisture and sends the reading to the server over Wi-Fi (phone hotspot).
The device never talks to the phone and never touches the database. The server decides
whether a session/reading is valid. Readings are write-once.

Real stack (software team): Next.js frontend, Spring Boot backend, PostgreSQL.
This repo is the HARDWARE team's FAKE server: stands in for the real backend so the ESP32 can
be tested before integration. In-memory only, no DB, no auth beyond the device key.

## Contract (integration guide section 3) — all names live in Contract.java
- POST /api/tests/start  {device_id, lot_id} -> {session_id, status:"open", expires_at}; 404 unknown, 409 busy
- POST /api/device/poll  headers X-Device-Id, X-Device-Key -> {session:null} | {session:{id, farmer_name, lot_code, expires_at}}; 401 wrong key, 403 disabled
- POST /api/device/reading  same headers, body {session_id, moisture_pct, zones{top,middle,bottom}, spread_pct, temp_c, calibration_version, firmware}
  -> {ok:true, reading_id}; 401 wrong key, 409 no open session, 422 out of range (moisture 5–40%), 400 wrong fields
  Duplicate send for an already-done session -> 200 with the same reading_id, not saved again.
- GET /api/tests/{id} -> {status: open|done|expired|cancelled, reading}
- Session lasts 5 min (fake.session.seconds). Device polls every 2 s. Units: one decimal.

Test-only extras (not in real contract): /fake/* (state, log, reset, settings, device enable/disable,
fault injection), POST /api/tests/{id}/cancel, optional farmer_name/lot_code on start.
Dashboard: src/main/resources/static/index.html. Self-test: ./test-fake-server.sh [baseUrl].

## Status (2026-10-13)
- Fake server written and running on the user's Mac (M4, VS Code); self-test ./test-fake-server.sh
  passes 31/31. Dashboard at http://localhost:8080 shows the address the ESP32 should use.
- Test setup: the user's Mac joins the friend's phone hotspot; the ESP32 talks to the Mac at the
  address shown at the top of the dashboard. The fake server only runs on the user's Mac.
- Done = all 14 tests on the dashboard checklist pass. Then the friend connects the device to the
  software team's real server.
- Waiting on software team to confirm: real URLs/field names, numeric vs UUID ids, header names,
  error bodies, duplicate handling, VM-0001 key, demo server address, farmer_name script, QR text.
  When they answer: change Contract.java + application.properties (+ ESP32 config.h). Nothing else.
- ESP32 firmware not written yet; it will go in a firmware/ folder in this repo (secrets in
  firmware/include/secrets.h, git-ignored). Friend (Windows laptop) has the hardware and writes the
  ESP32 code; screen being integrated.
  Watch out: ADC2 pins (GPIO 0,2,4,12-15,25-27) fail with Wi-Fi on — sensors must be on GPIO 32-39.

## Working style
User is new to IoT: explain simply, keep changes small, run ./test-fake-server.sh after changes.
