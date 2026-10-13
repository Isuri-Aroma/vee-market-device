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

## Firmware (for whoever writes the ESP32 code — lives in firmware/)
Server to test against: this fake server. Address is printed on start and shown at the top of the
dashboard (http://localhost:8080). ESP32 and the machine running the server must be on the same
Wi-Fi (the phone hotspot). Plain HTTP, JSON.

Every request sends headers X-Device-Id: VM-0001 and X-Device-Key: test-secret-0001
(real values may change; keep them in config.h / secrets.h, never hard-code in the logic).

Device behaviour to implement (this is what the 14 dashboard tests check):
- Idle: POST /api/device/poll every 2 s. {session:null} -> stay idle.
- Session present: show farmer_name + "Insert probe". Button press -> measure -> POST reading.
- Reading body: {session_id, moisture_pct, zones{top,middle,bottom}, spread_pct, temp_c,
  calibration_version, firmware}. One decimal everywhere. Reuse the SAME reading if resending.
- 200 ok -> show result, back to idle. 422 -> "Retry test". 409 -> session gone, back to idle.
- 401 wrong key -> "Device error", do NOT hammer the server (keep requests/min low).
- 403 disabled -> show error, poll slowly, recover when enabled again.
- No Wi-Fi / server down / timeout -> "Not sent", keep the reading, retry, never crash or hang.
- Power loss mid-test -> boot to idle; the server expires the session by itself.
- The device never talks to the phone and never touches the database.

Hardware (fill in the blanks; ask the user if unknown):
- Board: ESP32 (exact module: ___)
- Moisture sensors: 3 capacitive, zones top/middle/bottom, GPIO ___ / ___ / ___ (must be 32-39,
  ADC1 only: ADC2 pins fail while Wi-Fi is on)
- Temperature probe: type ___, GPIO ___
- Screen: model ___ (small; cannot show Sinhala, so English letters only), pins ___
- Button: GPIO ___   Buzzer: GPIO ___
- Files: firmware/include/config.h (server address, ids, poll interval), firmware/include/secrets.h
  (Wi-Fi name/password, device key; git-ignored, never commit).

## Working style
User is new to IoT: explain simply, keep changes small, run ./test-fake-server.sh after changes.
