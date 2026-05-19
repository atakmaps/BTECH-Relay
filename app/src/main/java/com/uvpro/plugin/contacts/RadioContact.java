package com.uvpro.plugin.contacts;

/**
 * Data model for a radio contact tracked on the ATAK map.
 *
 * Represents another radio operator seen via APRS, UV-PRO packets,
 * or other radio protocol.
 */
public class RadioContact {

    /** Callsign (e.g., "KI5ABC") */
    private String callsign;

    /** ATAK UID for this contact */
    private String uid;

    /** Last known position */
    private double latitude;
    private double longitude;
    private double altitude;  // meters HAE

    /** Movement */
    private double speed;     // m/s
    private double course;    // degrees

    /** Battery level (0-100) or -1 if unknown */
    private int battery;

    /** Timestamps */
    private long firstSeen;
    private long lastSeen;
    private long lastPositionUpdate;

    /** Packet statistics */
    private int packetCount;

    /** Contact status */
    private ContactStatus status;

    /** Source of data */
    private ContactSource source;

    /** Last APRS icon from a position report (for telemetry-only CoT refresh). */
    private Character lastAprsSymbolTable;
    private Character lastAprsSymbolCode;

    /** Formatted APRS packet metadata for the APRS details panel. */
    private String lastAprsDetailsText;

    public enum ContactStatus {
        /** Actively reporting */
        ACTIVE,
        /** No update within stale threshold */
        STALE,
        /** No update for a long time — may have gone offline */
        LOST
    }

    public enum ContactSource {
        /** Standard APRS from any APRS radio */
        APRS,
        /** UVPro custom protocol from another UVPro user */
        UVPRO,
        /** Unknown source */
        UNKNOWN
    }

    public RadioContact(String callsign) {
        this.callsign = callsign.trim().toUpperCase();
        this.uid = "ANDROID-" + this.callsign;
        this.altitude = -1;
        this.speed = -1;
        this.course = -1;
        this.battery = -1;
        this.firstSeen = System.currentTimeMillis();
        this.lastSeen = this.firstSeen;
        this.lastPositionUpdate = 0;
        this.packetCount = 0;
        this.status = ContactStatus.ACTIVE;
        this.source = ContactSource.UNKNOWN;
    }

    // --- Getters ---

    public String getCallsign() { return callsign; }
    public String getUid() { return uid; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getAltitude() { return altitude; }
    public double getSpeed() { return speed; }
    public double getCourse() { return course; }
    public int getBattery() { return battery; }
    public long getFirstSeen() { return firstSeen; }
    public long getLastSeen() { return lastSeen; }
    public long getLastPositionUpdate() { return lastPositionUpdate; }
    public int getPacketCount() { return packetCount; }
    public ContactStatus getStatus() { return status; }
    public ContactSource getSource() { return source; }

    public Character getLastAprsSymbolTable() { return lastAprsSymbolTable; }

    public Character getLastAprsSymbolCode() { return lastAprsSymbolCode; }

    public String getLastAprsDetailsText() { return lastAprsDetailsText; }

    // --- Setters ---

    public void setLatitude(double latitude) { this.latitude = latitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public void setAltitude(double altitude) { this.altitude = altitude; }
    public void setSpeed(double speed) { this.speed = speed; }
    public void setCourse(double course) { this.course = course; }
    public void setBattery(int battery) { this.battery = battery; }
    public void setSource(ContactSource source) { this.source = source; }

    /**
     * Remember APRS map symbol from the last position report so telemetry (no lat/lon)
     * can refresh the CoT without dropping {@code usericon}.
     */
    public void setLastAprsMapSymbol(Character table, Character code) {
        this.lastAprsSymbolTable = table;
        this.lastAprsSymbolCode = code;
    }

    public void setLastAprsDetailsText(String text) {
        this.lastAprsDetailsText = text;
    }

    /**
     * Update position data.
     */
    public void updatePosition(double lat, double lon, double alt,
                               double speed, double course) {
        this.latitude = lat;
        this.longitude = lon;
        if (alt >= 0) this.altitude = alt;
        if (speed >= 0) this.speed = speed;
        if (course >= 0) this.course = course;
        this.lastPositionUpdate = System.currentTimeMillis();
        touch();
    }

    /**
     * Mark contact as seen (updates lastSeen and packet count).
     */
    public void touch() {
        this.lastSeen = System.currentTimeMillis();
        this.packetCount++;
        this.status = ContactStatus.ACTIVE;
    }

    /**
     * Update status based on elapsed time.
     *
     * @param staleMs   Threshold for STALE status (see ContactTracker sweep)
     * @param lostMs    Threshold for LOST status (see ContactTracker sweep)
     */
    public void updateStatus(long staleMs, long lostMs) {
        long elapsed = System.currentTimeMillis() - lastSeen;
        if (elapsed > lostMs) {
            status = ContactStatus.LOST;
        } else if (elapsed > staleMs) {
            status = ContactStatus.STALE;
        } else {
            status = ContactStatus.ACTIVE;
        }
    }

    /**
     * Check if this contact has a valid position.
     */
    public boolean hasPosition() {
        return lastPositionUpdate > 0
                && latitude != 0.0 && longitude != 0.0;
    }

    /**
     * Get age in seconds since last seen.
     */
    public long getAgeSec() {
        return (System.currentTimeMillis() - lastSeen) / 1000;
    }

    @Override
    public String toString() {
        return "RadioContact{" + callsign
                + " pos=" + latitude + "," + longitude
                + " status=" + status
                + " pkts=" + packetCount
                + " age=" + getAgeSec() + "s}";
    }
}
