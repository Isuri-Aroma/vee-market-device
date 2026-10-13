package lk.veemarket.fake;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Test-only controls (NOT part of the contract, the real server will not have these).
 * Used by the dashboard at http://<mac-ip>:8080/ and by test-fake-server.sh.
 */
@RestController
@RequestMapping("/fake")
public class FakeControlController {

    private final Store store;

    public FakeControlController(Store store) { this.store = store; }

    @GetMapping("/state")
    public Map<String, Object> state() {
        synchronized (store) {
            store.expireOld();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("addresses", FakeServerApplication.localIPv4());
            out.put("settings", Map.of(
                    "session_seconds", store.sessionSeconds,
                    "moisture_min", store.moistureMin,
                    "moisture_max", store.moistureMax));

            List<Map<String, Object>> devs = new ArrayList<>();
            for (Store.Device d : store.devices.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("device_code", d.code);
                m.put("status", d.status);
                m.put("last_seen_at", store.iso(d.lastSeen));
                m.put("seconds_since_seen", d.lastSeen == null ? null : Duration.between(d.lastSeen, Instant.now()).toSeconds());
                m.put("polls", d.pollCount);
                m.put("rejected_last_60s", store.rejectedLastMinute(d));
                m.put("firmware", d.firmware);
                m.put("calibration_version", d.calibrationVersion);
                devs.add(m);
            }
            out.put("devices", devs);

            List<Map<String, Object>> ses = new ArrayList<>();
            for (Store.Session s : store.sessions.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id);
                m.put("device", s.deviceCode);
                m.put("farmer_name", s.farmerName);
                m.put("lot_code", s.lotCode);
                m.put("status", s.status);
                m.put("expires_in_s", "open".equals(s.status) ? Duration.between(Instant.now(), s.expiresAt).toSeconds() : null);
                m.put("reading_id", s.readingId);
                ses.add(0, m);
            }
            out.put("sessions", ses);

            List<Map<String, Object>> rs = new ArrayList<>();
            for (Store.Reading r : store.readings.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id);
                m.put("session_id", r.sessionId);
                m.put("received_at", store.iso(r.receivedAt));
                m.put("duplicates_ignored", r.duplicateCount);
                m.put("body", r.body);
                rs.add(0, m);
            }
            out.put("readings", rs);

            Map<String, Object> f = new LinkedHashMap<>();
            store.faults.forEach((k, v) -> f.put(k, Map.of("mode", v.mode, "remaining", v.remaining, "delay_ms", v.delayMs)));
            out.put("faults", f);
            return out;
        }
    }

    @GetMapping("/log")
    public List<Store.Event> log(@RequestParam(defaultValue = "0") long after) {
        return store.eventsAfter(after);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        store.reset();
        return Map.of("ok", true);
    }

    /** e.g. POST /fake/settings?session_seconds=30 */
    @PostMapping("/settings")
    public Map<String, Object> settings(@RequestParam(required = false) Integer session_seconds) {
        if (session_seconds != null) {
            store.sessionSeconds = session_seconds;
            store.log("FAKE", "Session length set to " + session_seconds + " s (applies to new sessions).", null, "warn");
        }
        return Map.of("session_seconds", store.sessionSeconds);
    }

    /** e.g. POST /fake/device/VM-0001?status=disabled */
    @PostMapping("/device/{code}")
    public ResponseEntity<Map<String, Object>> device(@PathVariable String code, @RequestParam String status) {
        synchronized (store) {
            Store.Device d = store.devices.get(code);
            if (d == null) return ResponseEntity.status(404).body(Map.of("error", "no such device"));
            if (!status.equals("active") && !status.equals("disabled"))
                return ResponseEntity.badRequest().body(Map.of("error", "status must be active or disabled"));
            d.status = status;
            store.log("FAKE", "Device " + code + " is now " + status + ".", null, "warn");
            return ResponseEntity.ok(Map.of("device", code, "status", status));
        }
    }

    /**
     * Make the server misbehave on purpose.
     * target = poll | reading
     * mode   = 500            reply 500 without doing anything
     *          slow           wait delay_ms, then behave normally
     *          save_then_500  (reading only) save the reading, then reply 500  -> tests duplicate handling
     *          save_then_slow (reading only) save, then wait delay_ms         -> tests device timeout + retry
     * times  = how many requests it applies to (default 1)
     */
    @PostMapping("/fault")
    public ResponseEntity<Map<String, Object>> fault(@RequestParam String target, @RequestParam String mode,
                                                     @RequestParam(defaultValue = "1") int times,
                                                     @RequestParam(defaultValue = "8000") int delay_ms) {
        if (!Set.of("poll", "reading").contains(target))
            return ResponseEntity.badRequest().body(Map.of("error", "target must be poll or reading"));
        if (!Set.of("500", "slow", "save_then_500", "save_then_slow").contains(mode))
            return ResponseEntity.badRequest().body(Map.of("error", "unknown mode"));
        synchronized (store) {
            Store.Fault f = new Store.Fault();
            f.mode = mode; f.remaining = times; f.delayMs = delay_ms;
            store.faults.put(target, f);
            store.log("FAKE", "Fault armed: next " + times + " " + target + " request(s) -> " + mode
                    + (mode.contains("slow") ? " (" + delay_ms + " ms)" : ""), null, "warn");
        }
        return ResponseEntity.ok(Map.of("target", target, "mode", mode, "times", times));
    }

    @DeleteMapping("/fault")
    public Map<String, Object> clearFaults() {
        synchronized (store) { store.faults.clear(); }
        store.log("FAKE", "All faults cleared.", null, "ok");
        return Map.of("ok", true);
    }
}
