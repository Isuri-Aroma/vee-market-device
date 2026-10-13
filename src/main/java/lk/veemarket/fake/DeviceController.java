package lk.veemarket.fake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.util.*;

import static lk.veemarket.fake.Contract.*;

/** The two endpoints the ESP32 calls. Guide section 3, parts B and C. */
@RestController
public class DeviceController {

    private final Store store;
    private final ObjectMapper json = new ObjectMapper();

    public DeviceController(Store store) { this.store = store; }

    // ============================================================ B. POLL
    @PostMapping(DEVICE_POLL)
    public ResponseEntity<Map<String, Object>> poll(
            @RequestHeader(value = H_DEVICE_ID, required = false) String deviceId,
            @RequestHeader(value = H_DEVICE_KEY, required = false) String deviceKey) {

        Store.Fault fault = store.takeFault("poll");
        if (fault != null) {
            if (fault.mode.equals("500")) {
                store.log("DEVICE", "POST " + DEVICE_POLL + " -> 500 (fault injected)", null, "warn");
                return reply(500, Map.of("error", "server_error"));
            }
            if (fault.mode.equals("slow")) {
                store.log("DEVICE", "POST " + DEVICE_POLL + " (fault: waiting " + fault.delayMs + " ms before replying)", null, "warn");
                sleep(fault.delayMs);
            }
        }

        Store.Device d = authenticate(deviceId, deviceKey, DEVICE_POLL);
        if (d == null) return reply(401, Map.of("error", "wrong_key"));
        if ("disabled".equals(d.status)) {
            store.noteRejected(d);
            store.log("DEVICE", "POST " + DEVICE_POLL + " from " + d.code + " -> 403 device disabled", null, "err");
            return reply(403, Map.of("error", "device_disabled"));
        }

        synchronized (store) {
            d.lastSeen = java.time.Instant.now();
            d.pollCount++;
            Store.Session s = store.openSessionFor(d.code);
            Map<String, Object> body = new LinkedHashMap<>();
            if (s == null) {
                body.put(F_SESSION, null);
                // Polls with nothing waiting are logged quietly (every 10th) so they don't flood the log.
                if (d.pollCount % 10 == 1) {
                    store.log("DEVICE", "POST " + DEVICE_POLL + " from " + d.code + " -> 200 nothing waiting  (poll #" + d.pollCount + ")", null, "ok");
                }
            } else {
                Map<String, Object> ses = new LinkedHashMap<>();
                ses.put(F_ID, s.id);
                ses.put(F_FARMER_NAME, s.farmerName);
                ses.put(F_LOT_CODE, s.lotCode);
                ses.put(F_EXPIRES_AT, store.iso(s.expiresAt));
                body.put(F_SESSION, ses);
                store.log("DEVICE", "POST " + DEVICE_POLL + " from " + d.code + " -> 200 session " + s.id + " waiting", toJson(body), "ok");
            }
            return reply(200, body);
        }
    }

    // ============================================================ C. READING
    @PostMapping(DEVICE_READING)
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> reading(
            @RequestHeader(value = H_DEVICE_ID, required = false) String deviceId,
            @RequestHeader(value = H_DEVICE_KEY, required = false) String deviceKey,
            @RequestBody(required = false) String raw) {

        Store.Fault fault = store.takeFault("reading");
        if (fault != null && fault.mode.equals("500")) {
            store.log("DEVICE", "POST " + DEVICE_READING + " -> 500 (fault injected, nothing saved)", raw, "warn");
            return reply(500, Map.of("error", "server_error"));
        }

        Store.Device d = authenticate(deviceId, deviceKey, DEVICE_READING);
        if (d == null) return reply(401, Map.of("error", "wrong_key"));
        if ("disabled".equals(d.status)) {
            store.noteRejected(d);
            store.log("DEVICE", "POST " + DEVICE_READING + " from " + d.code + " -> 403 device disabled", raw, "err");
            return reply(403, Map.of("error", "device_disabled"));
        }
        d.lastSeen = java.time.Instant.now();

        // ---- 1. Is the JSON exactly what section 3.C says? ----
        Map<String, Object> body;
        try {
            body = json.readValue(raw == null ? "" : raw, LinkedHashMap.class);
        } catch (Exception e) {
            store.log("DEVICE", "POST " + DEVICE_READING + " -> 400 body is not valid JSON", raw, "err");
            return reply(400, Map.of("error", "bad_json"));
        }
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkContract(body, problems, warnings);
        if (!problems.isEmpty()) {
            store.log("DEVICE", "POST " + DEVICE_READING + " -> 400 JSON does not match the contract",
                    "Received: " + raw + "\nProblems: " + String.join("; ", problems), "err");
            return reply(400, Map.of("error", "bad_request", "problems", problems));
        }

        Map<String, Object> result;
        int status;
        synchronized (store) {
            store.expireOld();
            long sessionId = ((Number) body.get(F_SESSION_ID)).longValue();
            Store.Session s = store.sessions.get(sessionId);

            // ---- 2. Duplicate (a retry of a reading we already saved) -> OK, not saved again ----
            if (s != null && s.deviceCode.equals(d.code) && "done".equals(s.status) && s.readingId != null) {
                Store.Reading r = store.readings.get(s.readingId);
                r.duplicateCount++;
                store.log("DEVICE", "POST " + DEVICE_READING + " -> 200 DUPLICATE of reading " + r.id + " (retry; not saved again)", raw, "warn");
                return reply(200, ok(r.id));
            }
            // ---- 3. Session must exist, be open, and belong to this device ----
            if (s == null || !"open".equals(s.status) || !s.deviceCode.equals(d.code)) {
                String why = s == null ? "no such session" : !s.deviceCode.equals(d.code) ? "session belongs to another device" : "session is " + s.status;
                store.log("DEVICE", "POST " + DEVICE_READING + " -> 409 no open session (" + why + "), nothing saved", raw, "err");
                return reply(409, Map.of("error", "no_open_session"));
            }
            // ---- 4. Values in range ----
            List<String> range = checkRange(body);
            if (!range.isEmpty()) {
                store.log("DEVICE", "POST " + DEVICE_READING + " -> 422 value out of range, nothing saved",
                        raw + "\n" + String.join("; ", range), "err");
                return reply(422, Map.of("error", "out_of_range", "problems", range));
            }
            // ---- 5. Save ----
            Store.Reading r = store.saveReading(s, body);
            d.firmware = String.valueOf(body.get(F_FIRMWARE));
            d.calibrationVersion = String.valueOf(body.get(F_CAL_VERSION));
            String detail = "Received: " + raw + (warnings.isEmpty() ? "\nContract check: PASS (exact match with section 3.C)" : "\nContract check: PASS with warnings: " + String.join("; ", warnings));
            store.log("DEVICE", "POST " + DEVICE_READING + " -> 200 SAVED reading " + r.id + " for session " + s.id + " (" + body.get(F_MOISTURE) + "%)", detail, warnings.isEmpty() ? "ok" : "warn");
            result = ok(r.id);
            status = 200;
        }

        // Fault: the reading is saved but the reply is lost -> the device should retry and get a duplicate OK.
        if (fault != null && fault.mode.equals("save_then_500")) {
            store.log("FAKE", "Fault: reading was SAVED but replying 500, so the device thinks it failed and retries.", null, "warn");
            return reply(500, Map.of("error", "server_error"));
        }
        if (fault != null && fault.mode.equals("save_then_slow")) {
            store.log("FAKE", "Fault: reading was SAVED, now waiting " + fault.delayMs + " ms before replying (device should time out and retry).", null, "warn");
            sleep(fault.delayMs);
        }
        if (fault != null && fault.mode.equals("slow")) sleep(fault.delayMs);
        return reply(status, result);
    }

    // ============================================================ helpers
    private Store.Device authenticate(String id, String key, String path) {
        if (id == null || key == null) {
            store.log("DEVICE", "POST " + path + " -> 401 missing " + H_DEVICE_ID + " or " + H_DEVICE_KEY + " header", null, "err");
            return null;
        }
        Store.Device d = store.devices.get(id);
        if (d == null) {
            store.log("DEVICE", "POST " + path + " from unknown device '" + id + "' -> 401", null, "err");
            return null;
        }
        boolean match = MessageDigest.isEqual(d.keyHash.getBytes(), Store.sha256(key).getBytes());
        if (!match) {
            store.noteRejected(d);
            int n = store.rejectedLastMinute(d);
            store.log("DEVICE", "POST " + path + " from " + id + " -> 401 WRONG KEY  (" + n + " rejected in last 60 s"
                    + (n > 6 ? " — device is hammering the server, it should back off!)" : ")"), null, n > 6 ? "err" : "warn");
            return null;
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private void checkContract(Map<String, Object> body, List<String> problems, List<String> warnings) {
        for (String f : READING_FIELDS) if (!body.containsKey(f)) problems.add("missing field '" + f + "'");
        for (String f : body.keySet()) if (!READING_FIELDS.contains(f)) warnings.add("unexpected field '" + f + "'");
        if (!problems.isEmpty()) return;

        if (!(body.get(F_SESSION_ID) instanceof Number)) problems.add("'" + F_SESSION_ID + "' must be a number");
        for (String f : List.of(F_MOISTURE, F_SPREAD, F_TEMP)) checkNumber(f, body.get(f), problems, warnings);
        for (String f : List.of(F_CAL_VERSION, F_FIRMWARE))
            if (!(body.get(f) instanceof String)) problems.add("'" + f + "' must be text");

        Object z = body.get(F_ZONES);
        if (!(z instanceof Map)) { problems.add("'" + F_ZONES + "' must be an object {top, middle, bottom}"); return; }
        Map<String, Object> zones = (Map<String, Object>) z;
        for (String f : ZONE_FIELDS) {
            if (!zones.containsKey(f)) problems.add("missing zone '" + f + "'");
            else checkNumber(F_ZONES + "." + f, zones.get(f), problems, warnings);
        }
        for (String f : zones.keySet()) if (!ZONE_FIELDS.contains(f)) warnings.add("unexpected zone '" + f + "'");
    }

    private void checkNumber(String name, Object v, List<String> problems, List<String> warnings) {
        if (!(v instanceof Number n)) { problems.add("'" + name + "' must be a number"); return; }
        double x = n.doubleValue();
        if (Math.abs(x * 10 - Math.round(x * 10)) > 1e-6) warnings.add("'" + name + "' = " + x + " should have one decimal place");
    }

    @SuppressWarnings("unchecked")
    private List<String> checkRange(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        double m = num(body.get(F_MOISTURE));
        if (m < store.moistureMin || m > store.moistureMax)
            out.add(F_MOISTURE + " " + m + " is outside " + store.moistureMin + "–" + store.moistureMax + "%");
        Map<String, Object> zones = (Map<String, Object>) body.get(F_ZONES);
        for (String f : ZONE_FIELDS) {
            double v = num(zones.get(f));
            if (v < store.moistureMin || v > store.moistureMax)
                out.add("zone " + f + " " + v + " is outside " + store.moistureMin + "–" + store.moistureMax + "%");
        }
        double sp = num(body.get(F_SPREAD));
        if (sp < 0) out.add(F_SPREAD + " cannot be negative");
        double t = num(body.get(F_TEMP));
        if (t < store.tempMin || t > store.tempMax) out.add(F_TEMP + " " + t + " is outside " + store.tempMin + "–" + store.tempMax + " °C");
        return out;
    }

    private static double num(Object o) { return ((Number) o).doubleValue(); }

    private Map<String, Object> ok(long readingId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(F_OK, true);
        m.put(F_READING_ID, readingId);
        return m;
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    private static ResponseEntity<Map<String, Object>> reply(int status, Map<String, Object> body) {
        return ResponseEntity.status(status).body(body);
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
