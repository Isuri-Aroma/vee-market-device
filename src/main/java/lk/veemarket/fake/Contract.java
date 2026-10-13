package lk.veemarket.fake;

import java.util.List;

/**
 * THE CONTRACT — every URL, header and field name the device and app use, in one place.
 * Source: "Vee Market: device and app integration guide", section 3.
 *
 * When the software team tells you their real names, change them HERE (and in the
 * ESP32 config.h) and nothing else.
 */
public final class Contract {
    private Contract() {}

    // ---- URLs ----
    public static final String APP_START_TEST   = "/api/tests/start";     // A. app -> server
    public static final String APP_GET_TEST     = "/api/tests/{id}";      // D. app -> server
    public static final String APP_CANCEL_TEST  = "/api/tests/{id}/cancel"; // (extra, for testing)
    public static final String DEVICE_POLL      = "/api/device/poll";     // B. device -> server
    public static final String DEVICE_READING   = "/api/device/reading";  // C. device -> server

    // ---- Device headers ----
    public static final String H_DEVICE_ID  = "X-Device-Id";
    public static final String H_DEVICE_KEY = "X-Device-Key";

    // ---- A. start test body ----
    public static final String F_DEVICE_ID = "device_id";
    public static final String F_LOT_ID    = "lot_id";

    // ---- B. poll reply ----
    public static final String F_SESSION     = "session";
    public static final String F_ID          = "id";
    public static final String F_FARMER_NAME = "farmer_name";
    public static final String F_LOT_CODE    = "lot_code";
    public static final String F_EXPIRES_AT  = "expires_at";

    // ---- C. reading body ----
    public static final String F_SESSION_ID   = "session_id";
    public static final String F_MOISTURE     = "moisture_pct";
    public static final String F_ZONES        = "zones";
    public static final String F_ZONE_TOP     = "top";
    public static final String F_ZONE_MIDDLE  = "middle";
    public static final String F_ZONE_BOTTOM  = "bottom";
    public static final String F_SPREAD       = "spread_pct";
    public static final String F_TEMP         = "temp_c";
    public static final String F_CAL_VERSION  = "calibration_version";
    public static final String F_FIRMWARE     = "firmware";

    public static final List<String> READING_FIELDS = List.of(
            F_SESSION_ID, F_MOISTURE, F_ZONES, F_SPREAD, F_TEMP, F_CAL_VERSION, F_FIRMWARE);
    public static final List<String> ZONE_FIELDS = List.of(F_ZONE_TOP, F_ZONE_MIDDLE, F_ZONE_BOTTOM);

    // ---- C. reading reply ----
    public static final String F_OK         = "ok";
    public static final String F_READING_ID = "reading_id";

    // ---- D. test status reply ----
    public static final String F_STATUS  = "status";   // open | done | expired | cancelled
    public static final String F_READING = "reading";
}
