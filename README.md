# Vee Market — fake server

Pretends to be the real Vee Market server so the ESP32 device can be tested before the
software team's server is ready. Spring Boot, no database (everything is in memory and is
wiped when you stop it). Follows section 3 of the device & app integration guide.

## 1. Install once (Mac)

```bash
brew install --cask temurin@21   # Java 21
brew install maven
java -version                    # should say 21
```

## 2. Run

```bash
cd vee-fake-server
mvn spring-boot:run
```

The first run downloads Spring Boot (a few minutes). When it is ready it prints:

```
 Dashboard on this Mac : http://localhost:8080/
 ESP32 / other laptop  : http://172.20.10.3:8080/
```

* Open the dashboard on your Mac. If macOS asks whether Java may accept incoming connections, click **Allow**.
* The second address (the one on the hotspot) is the one you put in the ESP32 firmware
  and the one your friend opens in his browser to check he can reach you.
* Stop with Ctrl+C.

## 3. Check the server before using the device

```bash
./test-fake-server.sh                              # on your Mac
./test-fake-server.sh http://172.20.10.3:8080      # from your friend's laptop (Git Bash on Windows)
```

It plays the ESP32 and the app with curl and should end with `0 failed`.
If it passes from your friend's laptop, the network is fine and the ESP32 will reach you too.

## 4. What the device must send

| | |
|---|---|
| Device ID header | `X-Device-Id: VM-0001` |
| Key header | `X-Device-Key: test-secret-0001` |
| Poll | `POST /api/device/poll` (no body), every 2 s |
| Reading | `POST /api/device/reading`, `Content-Type: application/json`, body as guide section 3.C |

## 5. Dashboard

* **Start test**: does what the farmer's app does (opens a session for VM-0001).
* **Cancel / Disable device / Session length 30 s**: for the expiry, cancel and disabled tests.
* **Make the server misbehave**: return errors, reply slowly, or save a reading and then pretend it failed, to test the device's retries.
* **Live log**: every request the device makes. A reading with exactly the right fields
  shows `Contract check: PASS`. Wrong or missing fields are listed.
* **Tests for today**: checklist with how to run each test and what counts as a pass.

## 6. When the software team answers

Change names in **`Contract.java`** (URLs, headers, field names) and the key in
**`application.properties`**, then make the same change in the ESP32 `config.h`.
Nothing else needs to change.

Not part of the real contract (test only): everything under `/fake/...`,
`POST /api/tests/{id}/cancel`, and the optional `farmer_name` / `lot_code` fields on start.
