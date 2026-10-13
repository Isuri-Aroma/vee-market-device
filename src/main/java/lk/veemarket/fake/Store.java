package lk.veemarket.fake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the fake server remembers, in memory. Restarting the server wipes it.
 * The real server keeps this in Postgres (DEVICE, TEST_SESSION, READING tables).
 */
@Component
public class Store {

    // ---------- data ----------
    public static class Device {
        public String code;
        public String keyHash;          // SHA-256 of the secret, like the real server should store
        public String status = "active"; // active | disabled
        public Instant lastSeen;
        public String firmware;
        public String calibrationVersion;
        public int pollCount;
        public final Deque<Instant> rejected = new ArrayDeque<>(); // times of 401/403 replies
    }

    public static class Session {
        public long id;
        public String deviceCode;
        public long lotId;
        public String farmerName;
        public String lotCode;
        public String status;            // open | done | expired | cancelled
        public Instant createdAt, expiresAt, doneAt;
        public Long readingId;
    }

    public static class Reading {
        public long id;
        public long sessionId;
        public String deviceCode;
        public long lotId;
        public Map<String, Object> body; // exactly what the device sent
        public Instant receivedAt;
        public int duplicateCount;
    }

    public static class Fault {
        public String mode = "none";
        public int remaining;
        public int delayMs = 8000;
    }

    public final Map<String, Device> devices = new LinkedHashMap<>();
    public final Map<Long, Session> sessions = new LinkedHashMap<>();
    public final Map<Long, Reading> readings = new LinkedHashMap<>();
    public final Map<String, Fault> faults = new HashMap<>(); // key: "poll" or "reading"
    private final AtomicLong sessionSeq = new AtomicLong(44);  // first session = 45, like the guide
    private final AtomicLong readingSeq = new AtomicLong(76);  // first reading = 77

    // ---------- settings (changeable at runtime from the dashboard) ----------
    public volatile int sessionSeconds;
    public volatile double moistureMin, moistureMax, tempMin, tempMax;
    public final String defaultFarmerName;
    public final ZoneId zone;
    private final String initialDeviceId, initialDeviceKey;
    private final int initialSessionSeconds;

    // ---------- event log ----------
    public static class Event {
        public long n;
        public String time;
        public String who;   // DEVICE / APP / FAKE
        public String line;  // e.g. "POST /api/device/poll -> 200"
        public String detail;
        public String level; // ok | warn | err
    }
    private final List<Event> events = new ArrayList<>();
    private final AtomicLong eventSeq = new AtomicLong();

    public Store(@Value("${fake.device.id}") String deviceId,
                 @Value("${fake.device.key}") String deviceKey,
                 @Value("${fake.session.seconds}") int sessionSeconds,
                 @Value("${fake.moisture.min}") double mMin,
                 @Value("${fake.moisture.max}") double mMax,
                 @Value("${fake.temp.min}") double tMin,
                 @Value("${fake.temp.max}") double tMax,
                 @Value("${fake.default.farmer-name}") String farmer,
                 @Value("${fake.timezone}") String tz) {
        this.initialDeviceId = deviceId;
        this.initialDeviceKey = deviceKey;
        this.initialSessionSeconds = sessionSeconds;
        this.moistureMin = mMin; this.moistureMax = mMax;
        this.tempMin = tMin; this.tempMax = tMax;
        this.defaultFarmerName = farmer;
        this.zone = ZoneId.of(tz);
        reset();
    }

    public synchronized void reset() {
        devices.clear(); sessions.clear(); readings.clear(); faults.clear(); events.clear();
        sessionSeq.set(44); readingSeq.set(76);
        sessionSeconds = initialSessionSeconds;
        addDevice(initialDeviceId, initialDeviceKey);
        log("FAKE", "Server reset. Device " + initialDeviceId + " registered.", null, "ok");
    }

    public synchronized Device addDevice(String code, String key) {
        Device d = new Device();
        d.code = code;
        d.keyHash = sha256(key);
        devices.put(code, d);
        return d;
    }

    // ---------- sessions ----------
    public synchronized Session newSession(String deviceCode, long lotId, String farmer, String lotCode) {
        Session s = new Session();
        s.id = sessionSeq.incrementAndGet();
        s.deviceCode = deviceCode;
        s.lotId = lotId;
        s.farmerName = farmer;
        s.lotCode = lotCode;
        s.status = "open";
        s.createdAt = Instant.now();
        s.expiresAt = s.createdAt.plusSeconds(sessionSeconds);
        sessions.put(s.id, s);
        return s;
    }

    /** Sessions expire lazily: whenever anyone looks at them. */
    public synchronized void expireOld() {
        Instant now = Instant.now();
        for (Session s : sessions.values()) {
            if ("open".equals(s.status) && now.isAfter(s.expiresAt)) {
                s.status = "expired";
                log("FAKE", "Session " + s.id + " expired (nobody sent a reading in time).", null, "warn");
            }
        }
    }

    public synchronized Session openSessionFor(String deviceCode) {
        expireOld();
        return sessions.values().stream()
                .filter(s -> s.deviceCode.equals(deviceCode) && "open".equals(s.status))
                .findFirst().orElse(null);
    }

    public synchronized Reading saveReading(Session s, Map<String, Object> body) {
        Reading r = new Reading();
        r.id = readingSeq.incrementAndGet();
        r.sessionId = s.id;
        r.deviceCode = s.deviceCode;
        r.lotId = s.lotId;
        r.body = body;
        r.receivedAt = Instant.now();
        readings.put(r.id, r);
        s.status = "done";
        s.doneAt = r.receivedAt;
        s.readingId = r.id;
        return r;
    }

    // ---------- faults ----------
    public synchronized Fault takeFault(String target) {
        Fault f = faults.get(target);
        if (f == null || "none".equals(f.mode) || f.remaining <= 0) return null;
        f.remaining--;
        Fault copy = new Fault();
        copy.mode = f.mode; copy.delayMs = f.delayMs; copy.remaining = f.remaining;
        if (f.remaining <= 0) faults.remove(target);
        return copy;
    }

    // ---------- auth ----------
    public static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public synchronized void noteRejected(Device d) {
        Instant now = Instant.now();
        d.rejected.addLast(now);
        while (!d.rejected.isEmpty() && d.rejected.peekFirst().isBefore(now.minusSeconds(60))) d.rejected.removeFirst();
    }

    public synchronized int rejectedLastMinute(Device d) {
        Instant cutoff = Instant.now().minusSeconds(60);
        return (int) d.rejected.stream().filter(t -> t.isAfter(cutoff)).count();
    }

    // ---------- time ----------
    public String iso(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t.truncatedTo(ChronoUnit.SECONDS), zone).toString();
    }

    // ---------- log ----------
    public synchronized void log(String who, String line, String detail, String level) {
        Event e = new Event();
        e.n = eventSeq.incrementAndGet();
        e.time = OffsetDateTime.now(zone).toLocalTime().truncatedTo(ChronoUnit.MILLIS).toString();
        e.who = who; e.line = line; e.detail = detail; e.level = level;
        events.add(e);
        if (events.size() > 500) events.remove(0);
        String colour = switch (level) { case "err" -> "\u001B[31m"; case "warn" -> "\u001B[33m"; default -> "\u001B[32m"; };
        System.out.println(colour + "[" + e.time + "] " + who + "  " + line + "\u001B[0m");
        if (detail != null && !detail.isBlank()) System.out.println("            " + detail.replace("\n", "\n            "));
    }

    public synchronized List<Event> eventsAfter(long n) {
        return events.stream().filter(e -> e.n > n).toList();
    }
}
