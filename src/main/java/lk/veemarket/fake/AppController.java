package lk.veemarket.fake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

import static lk.veemarket.fake.Contract.*;

/**
 * Plays the farmer's app: start a test, check its result. Guide section 3, parts A and D.
 * There are no real farmers or lots here, so "lot not yours" (403) is never returned.
 */
@RestController
public class AppController {

    private final Store store;
    private final ObjectMapper json = new ObjectMapper();

    public AppController(Store store) { this.store = store; }

    // ============================================================ A. START TEST
    @PostMapping(APP_START_TEST)
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !body.containsKey(F_DEVICE_ID) || !body.containsKey(F_LOT_ID)) {
            store.log("APP", "POST " + APP_START_TEST + " -> 400 need " + F_DEVICE_ID + " and " + F_LOT_ID, String.valueOf(body), "err");
            return ResponseEntity.badRequest().body(Map.of("error", "need device_id and lot_id"));
        }
        String deviceId = String.valueOf(body.get(F_DEVICE_ID));
        long lotId = ((Number) body.get(F_LOT_ID)).longValue();
        // Extras for testing (not in the contract): choose the name/lot code shown on the screen.
        String farmer = body.containsKey("farmer_name") ? String.valueOf(body.get("farmer_name")) : store.defaultFarmerName;
        String lotCode = body.containsKey("lot_code") ? String.valueOf(body.get("lot_code")) : "L" + lotId;

        synchronized (store) {
            Store.Device d = store.devices.get(deviceId);
            if (d == null) {
                store.log("APP", "POST " + APP_START_TEST + " device " + deviceId + " -> 404 device unknown", null, "err");
                return ResponseEntity.status(404).body(Map.of("error", "device_unknown"));
            }
            if ("disabled".equals(d.status)) {
                store.log("APP", "POST " + APP_START_TEST + " device " + deviceId + " -> 409 device disabled", null, "err");
                return ResponseEntity.status(409).body(Map.of("error", "device_disabled"));
            }
            Store.Session busy = store.openSessionFor(deviceId);
            if (busy != null) {
                store.log("APP", "POST " + APP_START_TEST + " device " + deviceId + " -> 409 device busy (session " + busy.id + " is open)", null, "err");
                return ResponseEntity.status(409).body(Map.of("error", "device_busy"));
            }
            Store.Session s = store.newSession(deviceId, lotId, farmer, lotCode);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(F_SESSION_ID, s.id);
            out.put(F_STATUS, s.status);
            out.put(F_EXPIRES_AT, store.iso(s.expiresAt));
            store.log("APP", "POST " + APP_START_TEST + " -> 200 session " + s.id + " opened for " + deviceId
                    + " (farmer " + farmer + ", lot " + lotCode + ", expires in " + store.sessionSeconds + " s)", toJson(out), "ok");
            return ResponseEntity.ok(out);
        }
    }

    // ============================================================ D. CHECK RESULT
    @GetMapping(APP_GET_TEST)
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        synchronized (store) {
            store.expireOld();
            Store.Session s = store.sessions.get(id);
            if (s == null) return ResponseEntity.status(404).body(Map.of("error", "no_such_test"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(F_STATUS, s.status);
            Object reading = null;
            if (s.readingId != null) {
                Store.Reading r = store.readings.get(s.readingId);
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put(F_MOISTURE, r.body.get(F_MOISTURE));
                rm.put(F_ZONES, r.body.get(F_ZONES));
                rm.put(F_SPREAD, r.body.get(F_SPREAD));
                rm.put(F_TEMP, r.body.get(F_TEMP));
                rm.put("received_at", store.iso(r.receivedAt));
                reading = rm;
            }
            out.put(F_READING, reading);
            return ResponseEntity.ok(out);
        }
    }

    // ============================================================ extra: cancel
    @PostMapping(APP_CANCEL_TEST)
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable long id) {
        synchronized (store) {
            store.expireOld();
            Store.Session s = store.sessions.get(id);
            if (s == null) return ResponseEntity.status(404).body(Map.of("error", "no_such_test"));
            if (!"open".equals(s.status)) return ResponseEntity.status(409).body(Map.of("error", "test_is_" + s.status));
            s.status = "cancelled";
            store.log("APP", "Session " + id + " cancelled by the app. Device should go back to idle on its next poll.", null, "warn");
            return ResponseEntity.ok(Map.of(F_STATUS, "cancelled"));
        }
    }

    private String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
