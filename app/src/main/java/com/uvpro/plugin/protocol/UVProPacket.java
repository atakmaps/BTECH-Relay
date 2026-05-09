package com.uvpro.plugin.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UV-PRO custom packet format for efficient data transfer over radio.
 *
 * TLV (Type-Length-Value) structure:
 *   [Type (1 byte)] [Length (2 bytes, big-endian)] [Value (N bytes)]
 *
 * Type codes:
 *   0x01 = CoT XML (gzipped)
 *   0x02 = Chat message
 *   0x03 = GPS position (compact binary, 22 bytes)
 *   0x04 = Ping/discovery
 *   0x05 = Acknowledgment
 *   0x06 = CoT fragment (for large CoT that exceeds one frame)
 *
 * This sits INSIDE the AX.25 info field, which sits INSIDE a KISS frame:
 *   KISS → AX.25 → UVProPacket
 */
public class UVProPacket {

    /** Monotonic per-radio-session chat ids ( millis-based ids collided and confused ATAK). */
    private static final AtomicInteger CHAT_MESSAGE_ID = new AtomicInteger(1);

    // Packet type codes
    public static final byte TYPE_COT = 0x01;
    public static final byte TYPE_CHAT = 0x02;
    public static final byte TYPE_GPS = 0x03;
    public static final byte TYPE_PING = 0x04;
    public static final byte TYPE_ACK = 0x05;
    public static final byte TYPE_COT_FRAGMENT = 0x06;

    /** GeoChat delivered receipt (CoT type {@code b-t-f-d}). */
    public static final byte ACK_KIND_DELIVERED = 1;
    /** GeoChat read receipt */
    public static final byte ACK_KIND_READ = 2;

    private byte type;
    private byte[] payload;

    public UVProPacket(byte type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    /**
     * Encode this packet into bytes for the AX.25 info field.
     */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(3 + payload.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(type);
        buf.putShort((short) payload.length);
        buf.put(payload);
        return buf.array();
    }

    /**
     * Decode an UVProPacket from raw bytes.
     */
    public static UVProPacket decode(byte[] data) {
        if (data == null || data.length < 3) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.BIG_ENDIAN);

        byte type = buf.get();
        int length = buf.getShort() & 0xFFFF;

        if (buf.remaining() < length) return null;

        byte[] payload = new byte[length];
        buf.get(payload);

        return new UVProPacket(type, payload);
    }

    // --- Compact GPS Position encoding/decoding ---

    /**
     * Maximum UTF-8 length for embedded sender ATAK {@code locationTeam}; keeps frames small.
     */
    private static final int GPS_TEAM_EXT_MAX_CHARS = 48;

    /**
     * Create a compact GPS position packet (22 bytes).
     *
     * Format:
     *   Callsign (6 bytes, ASCII padded)
     *   Latitude (4 bytes, int32, degrees * 1e7)
     *   Longitude (4 bytes, int32, degrees * 1e7)
     *   Altitude (2 bytes, int16, meters)
     *   Speed (2 bytes, uint16, m/s * 100)
     *   Course (2 bytes, uint16, degrees * 100)
     *   Flags (1 byte)
     *   Battery (1 byte, percentage)
     * Optional trailing UTF-8 (legacy + v2): {@code '|' + full_callsign + [ '\0' + sender_team ]}
     */
    public static UVProPacket createGpsPacket(String callsign, String fullCallsign,
                                                   double lat, double lon,
                                                   double alt, double speed,
                                                   double course, int battery,
                                                   String senderAtakTeam) {
        ByteBuffer buf = ByteBuffer.allocate(22);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Callsign (6 bytes, padded)
        byte[] callBytes = (callsign + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        buf.put(callBytes);

        // Lat/lon as fixed-point integers
        buf.putInt((int) (lat * 1e7));
        buf.putInt((int) (lon * 1e7));

        // Altitude (meters, clamped to int16 range)
        buf.putShort((short) Math.max(-32768, Math.min(32767, (int) alt)));

        // Speed (m/s * 100)
        buf.putShort((short) Math.max(0, Math.min(65535, (int) (speed * 100))));

        // Course (degrees * 100)
        buf.putShort((short) Math.max(0, Math.min(36000, (int) (course * 100))));

        // Flags
        byte flags = 0x01; // hasGPS
        if (battery >= 0) flags |= 0x04; // hasBattery
        buf.put(flags);

        // Battery
        buf.put((byte) Math.max(0, Math.min(100, battery)));

        byte[] base = buf.array();

        byte[] full = fullCallsign != null ? fullCallsign.getBytes(
                java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        byte[] teamExt = null;
        if (senderAtakTeam != null) {
            String t = senderAtakTeam.trim();
            if (!t.isEmpty()) {
                if (t.length() > GPS_TEAM_EXT_MAX_CHARS) {
                    t = t.substring(0, GPS_TEAM_EXT_MAX_CHARS);
                }
                teamExt = t.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        int extLen = 1 + full.length + (teamExt != null ? 1 + teamExt.length : 0);
        byte[] out = new byte[base.length + extLen];
        System.arraycopy(base, 0, out, 0, base.length);
        int off = base.length;
        out[off++] = (byte) '|';
        if (full.length > 0) {
            System.arraycopy(full, 0, out, off, full.length);
            off += full.length;
        }
        if (teamExt != null) {
            out[off++] = (byte) '\0';
            System.arraycopy(teamExt, 0, out, off, teamExt.length);
        }
        return new UVProPacket(TYPE_GPS, out);
    }

    /**
     * Decode a compact GPS position from a GPS packet payload.
     */
    public static GpsData decodeGpsPayload(byte[] payload) {
        if (payload == null || payload.length < 22) return null;

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);

        GpsData gps = new GpsData();

        byte[] callBytes = new byte[6];
        buf.get(callBytes);
        gps.callsign = new String(callBytes,
                java.nio.charset.StandardCharsets.US_ASCII).trim();

        gps.latitude = buf.getInt() / 1e7;
        gps.longitude = buf.getInt() / 1e7;
        gps.altitude = buf.getShort();
        gps.speed = (buf.getShort() & 0xFFFF) / 100.0;
        gps.course = (buf.getShort() & 0xFFFF) / 100.0;
        gps.flags = buf.get();
        gps.battery = buf.get() & 0xFF;

        // Optional: '|' + full callsign UTF-8 + optional '\0' + sender ATAK team (matches native SA)
        if (payload.length > 22) {
            try {
                String extra = new String(payload, 22, payload.length - 22,
                        java.nio.charset.StandardCharsets.UTF_8);
                if (extra.startsWith("|")) {
                    String rest = extra.substring(1);
                    int nul = rest.indexOf('\0');
                    if (nul >= 0) {
                        gps.callsign = rest.substring(0, nul).trim();
                        gps.teamColor = rest.substring(nul + 1).trim();
                        if (gps.teamColor.isEmpty()) {
                            gps.teamColor = null;
                        }
                    } else {
                        gps.callsign = rest.trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return gps;
    }

    /**
     * Allocate the next wire chat {@code messageId} (same sequence as {@link #createChatPacket}).
     */
    public static int allocateChatWireMessageId() {
        int mid = CHAT_MESSAGE_ID.getAndIncrement();
        if (mid == 0) {
            mid = CHAT_MESSAGE_ID.getAndIncrement();
        }
        return mid;
    }

    /**
     * Create a chat message packet (auto-generates messageId).
     */
    public static UVProPacket createChatPacket(String sender,
                                                    String chatroom,
                                                    String message) {
        return createChatPacket(sender, chatroom, allocateChatWireMessageId(), message);
    }

    /**
     * Create a chat message packet.
     */
    public static UVProPacket createChatPacket(String sender,
                                                    String chatroom,
                                                    int messageId,
                                                    String message) {
        byte[] senderBytes = (sender + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] roomBytes = (chatroom + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] msgBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(16 + msgBytes.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(senderBytes);
        buf.put(roomBytes);
        buf.putInt(messageId);
        buf.put(msgBytes);

        return new UVProPacket(TYPE_CHAT, buf.array());
    }

    /**
     * GeoChat delivered/read acknowledgment ({@link #TYPE_ACK}).
     * Payload: BE int wire {@code messageId} + {@link #ACK_KIND_DELIVERED} or {@link #ACK_KIND_READ}.
     */
    public static UVProPacket createChatAckPacket(int wireMessageId, byte ackKind) {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(wireMessageId);
        buf.put(ackKind);
        return new UVProPacket(TYPE_ACK, buf.array());
    }

    public static AckPayload decodeAckPayload(byte[] payload) {
        if (payload == null || payload.length < 5) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        AckPayload a = new AckPayload();
        a.wireMessageId = buf.getInt();
        a.kind = buf.get();
        if (a.kind != ACK_KIND_DELIVERED && a.kind != ACK_KIND_READ) {
            return null;
        }
        return a;
    }

    /**
     * Create a ping/discovery packet.
     */
    public static UVProPacket createPingPacket(String callsign) {
        byte[] callBytes = (callsign + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return new UVProPacket(TYPE_PING, callBytes);
    }

    // --- Getters ---

    public byte getType() { return type; }
    public byte[] getPayload() { return payload; }

    /**
     * Compact GPS data structure.
     */
    public static class GpsData {
        public String callsign;
        /** Sender's ATAK {@code locationTeam} when carried on-wire; null if legacy packet */
        public String teamColor;
        public double latitude;
        public double longitude;
        public double altitude;
        public double speed;
        public double course;
        public byte flags;
        public int battery;
    }

    public static class AckPayload {
        public int wireMessageId;
        public byte kind;
    }
}
