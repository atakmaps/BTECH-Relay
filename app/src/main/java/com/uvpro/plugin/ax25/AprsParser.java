package com.uvpro.plugin.ax25;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses APRS packets from AX.25 info fields.
 *
 * Supports:
 * - Uncompressed position: {@code !} {@code =} {@code /} {@code @}
 * - Compressed position (same leading types; body matches APRS101 compressed form)
 * - Mic-E position: leading backtick or ASCII 39 (single quote) plus 6-char destination field
 * - Third-party wrap: leading brace-right with embedded CALL&gt;TO,PATH:payload
 * - APRS object reports: leading {@code ;} (position portion same as above)
 * - Telemetry: {@code T#} reports (parsed via {@link #parseTelemetry}; no lat/lon)
 * - Messages (for chat bridging)
 *
 * Reference: APRS101 / aprslib (rossengeorgiev) decode logic.
 */
public class AprsParser {

    private static final String TAG = "UVPro.APRS";

    /** Mic-E: first 8 bytes of the info field after the data-type character (aprslib). */
    private static final Pattern MIC_E_BODY_PREFIX = Pattern.compile(
            "^[\\x26-\\x7f][\\x26-\\x61][\\x1c-\\x7f]{2}[\\x1c-\\x7d][\\x1c-\\x7f][\\x21-\\x7e][/\\\\0-9A-Z]");

    /** Mic-E destination: 6 chars, digits in fixed positions (aprslib). */
    private static final Pattern MIC_E_DEST = Pattern.compile("^[0-9A-Z]{3}[0-9L-Z]{3}$");

    /** Compressed report: 13 chars per APRS101 / aprslib. */
    private static final Pattern COMPRESSED_13 = Pattern.compile(
            "^[/\\\\A-Za-j][!-\\x7C]{8}[!-{][\\x20-\\x7C]{3}$");

    /**
     * Parsed APRS position data.
     */
    public static class AprsPosition {
        public String callsign;
        public int ssid;
        public double latitude;
        public double longitude;
        public double altitude;    // meters, -1 if unknown
        public double speed;       // m/s, -1 if unknown
        public double course;      // degrees, -1 if unknown
        public String comment;     // APRS comment field
        public char symbol;        // APRS symbol character
        public char symbolTable;   // APRS symbol table ('/' or '\\')
        /** Populated for {@code _} weather stations and comment weather tails. */
        public AprsWeather weather;
    }

    /**
     * Parsed APRS message.
     */
    public static class AprsMessage {
        public String fromCallsign;
        public String toCallsign;
        public String message;
        public String messageId;   // For acknowledgment
    }

    /**
     * Parsed APRS telemetry report ({@code T#SEQ,A1..A5,BITS}).
     *
     * <p>Values are raw 8-bit analog channel payloads (0–255) and an 8-bit digital
     * bit mask string (MSB first per APRS convention).</p>
     */
    public static final class AprsTelemetry {
        /** Sequence / project id field from the packet (often 3 digits). */
        public final String sequence;
        public final int[] analog = new int[5];
        /** Eight {@code '0'} / {@code '1'} characters. */
        public final String binaryBits;

        public AprsTelemetry(String sequence, int[] analog, String binaryBits) {
            this.sequence = sequence;
            System.arraycopy(analog, 0, this.analog, 0, 5);
            this.binaryBits = binaryBits;
        }

        /** Short human-readable summary for map remarks / logs. */
        public String formatSummary() {
            StringBuilder sb = new StringBuilder(96);
            sb.append("APRS telemetry #").append(sequence);
            sb.append(" A=").append(analog[0]).append(',').append(analog[1])
                    .append(',').append(analog[2]).append(',').append(analog[3])
                    .append(',').append(analog[4]);
            sb.append(" bits=").append(binaryBits);
            return sb.toString();
        }
    }

    /**
     * Parse a standard APRS telemetry frame ({@code T#...}).
     *
     * @return parsed telemetry or null if not a {@code T#} report
     */
    public static AprsTelemetry parseTelemetry(String info) {
        if (info == null) {
            return null;
        }
        String s = info.trim();
        if (!s.startsWith("T#")) {
            return null;
        }
        String rest = s.substring(2);
        String[] p = rest.split(",", 7);
        if (p.length != 7) {
            return null;
        }
        String seq = p[0].trim();
        String bits = p[6].trim();
        if (bits.length() != 8 || !bits.matches("[01]{8}")) {
            return null;
        }
        int[] a = new int[5];
        try {
            for (int i = 0; i < 5; i++) {
                a[i] = Integer.parseInt(p[i + 1].trim());
                if (a[i] < 0 || a[i] > 999999) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return new AprsTelemetry(seq, a, bits);
    }

    /**
     * Embedded APRS inside a third-party {@code }} wrapper.
     */
    public static final class ThirdPartyInner {
        public final String callsign;
        public final int ssid;
        /** First hop in path (Mic-E encoded destination, {@code APRS}, etc.). */
        public final String toDest;
        public final String payload;

        public ThirdPartyInner(String callsign, int ssid, String toDest, String payload) {
            this.callsign = callsign;
            this.ssid = ssid;
            this.toDest = toDest;
            this.payload = payload;
        }
    }

    /**
     * Try to parse an APRS position from an AX.25 frame's info field (no Mic-E destination).
     */
    public static AprsPosition parsePosition(String callsign, int ssid, String info) {
        return parsePosition(callsign, ssid, info, "");
    }

    /**
     * Try to parse an APRS position from an AX.25 frame's info field.
     *
     * @param destCallsign AX.25 destination (6-char Mic-E encoding when present); may be empty
     */
    public static AprsPosition parsePosition(String callsign, int ssid,
                                             String info, String destCallsign) {
        if (info == null || info.isEmpty()) {
            return null;
        }
        try {
            char dataType = info.charAt(0);
            if (dataType == '`' || dataType == '\'') {
                String dst6 = micEDestinationBase6(destCallsign);
                if (dst6 == null) {
                    return null;
                }
                return finish(parseMicE(callsign, ssid, dst6, info.substring(1)));
            }
            if (dataType == ';') {
                return finish(parseObjectPosition(info));
            }
            switch (dataType) {
                case '!':
                case '=':
                    return finish(parseAfterDataType(callsign, ssid, info, 1));
                case '/':
                case '@':
                    if (info.length() > 8) {
                        return finish(parseAfterDataType(callsign, ssid, info, 8));
                    }
                    break;
                case ':':
                    return null;
                case 'T':
                    // Telemetry (T#...) is handled in PacketRouter via parseTelemetry().
                    return null;
                default:
                    Log.d(TAG, "Unhandled APRS data type: " + dataType);
                    return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse APRS position: " + e.getMessage());
        }
        return null;
    }

    private static AprsPosition finish(AprsPosition pos) {
        AprsWeatherParser.enrichPosition(pos);
        return pos;
    }

    /**
     * Unwrap {@code }CALL>TO,PATH:payload} third-party traffic for re-parsing the inner payload.
     */
    public static ThirdPartyInner unwrapThirdParty(String info) {
        if (info == null || info.length() < 4 || info.charAt(0) != '}') {
            return null;
        }
        String inner = info.substring(1);
        int colon = inner.indexOf(':');
        if (colon <= 0 || colon >= inner.length() - 1) {
            return null;
        }
        String header = inner.substring(0, colon);
        String payload = inner.substring(colon + 1);
        int gt = header.indexOf('>');
        if (gt <= 0 || gt >= header.length() - 1) {
            return null;
        }
        String fromField = header.substring(0, gt);
        String pathField = header.substring(gt + 1);
        int dash = fromField.indexOf('-');
        String baseCall = dash > 0 ? fromField.substring(0, dash) : fromField;
        int ssid = 0;
        if (dash > 0 && dash < fromField.length() - 1) {
            try {
                ssid = Integer.parseInt(fromField.substring(dash + 1));
            } catch (NumberFormatException ignored) {
                ssid = 0;
            }
        }
        String[] hops = pathField.split(",");
        if (hops.length == 0 || hops[0].isEmpty()) {
            return null;
        }
        String toDest = hops[0].trim();
        if (toDest.endsWith("*")) {
            toDest = toDest.substring(0, toDest.length() - 1);
        }
        toDest = toDest.toUpperCase(Locale.US);
        return new ThirdPartyInner(baseCall, ssid, toDest, payload);
    }

    private static AprsPosition parseAfterDataType(String callsign, int ssid,
                                                   String info, int offset) {
        if (info.length() < offset + 1) {
            return null;
        }
        String body = info.substring(offset);
        AprsPosition compressed = tryParseCompressedPosition(callsign, ssid, body);
        if (compressed != null) {
            return compressed;
        }
        AprsPosition pos = new AprsPosition();
        pos.callsign = callsign;
        pos.ssid = ssid;
        pos.altitude = -1;
        pos.speed = -1;
        pos.course = -1;
        return parseUncompressedPosition(pos, info, offset);
    }

    /**
     * APRS object report: {@code ;object9ch*timestamp!...} or {@code _} live flag.
     */
    private static AprsPosition parseObjectPosition(String info) {
        if (info.length() < 19) {
            return null;
        }
        char flag = info.charAt(10);
        if (flag != '*' && flag != '_') {
            return null;
        }
        String objectName = info.substring(1, 10).trim();
        if (objectName.isEmpty()) {
            return null;
        }
        int posOffset = 17;
        if (info.length() < posOffset + 1) {
            return null;
        }
        AprsPosition pos = parseAfterDataType(objectName, 0, info, posOffset);
        if (pos != null) {
            pos.callsign = objectName;
            pos.ssid = 0;
        }
        return pos;
    }

    private static AprsPosition tryParseCompressedPosition(String callsign, int ssid, String body) {
        if (body.length() < 13) {
            return null;
        }
        String head = body.substring(0, 13);
        if (!COMPRESSED_13.matcher(head).matches()) {
            return null;
        }
        long latVal = base91ToLong(head.substring(1, 5));
        long lonVal = base91ToLong(head.substring(5, 9));
        if (latVal < 0 || lonVal < 0) {
            return null;
        }
        AprsPosition pos = new AprsPosition();
        pos.callsign = callsign;
        pos.ssid = ssid;
        pos.altitude = -1;
        pos.speed = -1;
        pos.course = -1;
        pos.symbolTable = head.charAt(0);
        pos.symbol = head.charAt(9);
        pos.latitude = 90.0 - (latVal / 380926.0);
        pos.longitude = -180.0 + (lonVal / 190463.0);

        int c1 = head.charAt(10) - 33;
        int s1 = head.charAt(11) - 33;
        int ctype = head.charAt(12) - 33;
        if (c1 >= 0 && s1 >= 0) {
            if ((ctype & 0x18) == 0x10) {
                pos.altitude = (Math.pow(1.002, c1 * 91 + s1)) * 0.3048;
            } else if (!AprsWeatherParser.isWeatherSymbol(pos.symbolTable, pos.symbol) && c1 <= 89) {
                pos.course = (c1 == 0) ? 360 : c1 * 4;
                double speedKmh = (Math.pow(1.08, s1) - 1.0) * 1.852;
                pos.speed = speedKmh / 3.6;
            }
        }
        if (body.length() > 13) {
            pos.comment = body.substring(13);
        }
        if (Double.isNaN(pos.latitude) || Double.isNaN(pos.longitude)) {
            return null;
        }
        return pos;
    }

    private static long base91ToLong(String s) {
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '!' || c > '~') {
                return -1;
            }
            v = v * 91 + (c - 33);
        }
        return v;
    }

    private static String micEDestinationBase6(String destCallsign) {
        if (destCallsign == null) {
            return null;
        }
        int dash = destCallsign.indexOf('-');
        String base = dash > 0 ? destCallsign.substring(0, dash) : destCallsign;
        base = base.trim().toUpperCase();
        if (base.length() < 6) {
            base = (base + "      ").substring(0, 6);
        } else if (base.length() > 6) {
            base = base.substring(0, 6);
        }
        if (!MIC_E_DEST.matcher(base).matches()) {
            return null;
        }
        return base;
    }

    private static AprsPosition parseMicE(String callsign, int ssid, String dst6, String body) {
        try {
            if (body.length() < 8) {
                return null;
            }
            String prefix8 = body.substring(0, 8);
            if (!MIC_E_BODY_PREFIX.matcher(prefix8).matches()) {
                return null;
            }
            AprsPosition pos = new AprsPosition();
            pos.callsign = callsign;
            pos.ssid = ssid;
            pos.altitude = -1;
            pos.speed = -1;
            pos.course = -1;
            pos.symbol = body.charAt(6);
            pos.symbolTable = body.charAt(7);

            StringBuilder tmpDst = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                char ch = dst6.charAt(i);
                if (ch == 'K' || ch == 'L' || ch == 'Z') {
                    tmpDst.append(' ');
                } else if (ch > 'L' && ch <= 'Y') {
                    tmpDst.append((char) (ch - 32));
                } else if (ch > '9' && ch < 'K') {
                    tmpDst.append((char) (ch - 17));
                } else {
                    tmpDst.append(ch);
                }
            }
            String tmpRaw = tmpDst.toString();
            if (tmpRaw.length() != 6) {
                return null;
            }
            int posAmbiguity = countMicELatitudeTrailingSpaces(tmpRaw);
            if (posAmbiguity > 4) {
                return null;
            }
            char[] t = tmpRaw.toCharArray();
            if (posAmbiguity > 0) {
                if (posAmbiguity >= 4) {
                    t[2] = '3';
                } else {
                    t[6 - posAmbiguity] = '5';
                }
            }
            String tmp = new String(t);
            String degStr = tmp.substring(0, 2).replace(' ', '0');
            double latMinutes = Double.parseDouble(
                    (tmp.substring(2, 4) + "." + tmp.substring(4, 6)).replace(" ", "0"));
            double latitude = Integer.parseInt(degStr) + latMinutes / 60.0;
            if (dst6.charAt(3) <= 'L') {
                latitude = -latitude;
            }
            pos.latitude = latitude;

            double longitude = (body.charAt(0) - 28);
            if (dst6.charAt(4) >= 'P') {
                longitude += 100;
            }
            if (longitude >= 180 && longitude <= 189) {
                longitude -= 80;
            } else if (longitude >= 190 && longitude <= 199) {
                longitude -= 190;
            }
            double lngMinutes = body.charAt(1) - 28.0;
            if (lngMinutes >= 60) {
                lngMinutes -= 60;
            }
            lngMinutes += (body.charAt(2) - 28.0) / 100.0;
            if (posAmbiguity == 4) {
                lngMinutes = 30;
            } else if (posAmbiguity == 3) {
                lngMinutes = (Math.floor(lngMinutes / 10) + 0.5) * 10;
            } else if (posAmbiguity == 2) {
                lngMinutes = Math.floor(lngMinutes) + 0.5;
            } else if (posAmbiguity == 1) {
                lngMinutes = (Math.floor(lngMinutes * 10) + 0.5) / 10.0;
            }
            longitude += lngMinutes / 60.0;
            if (dst6.charAt(5) >= 'P') {
                longitude = -longitude;
            }
            pos.longitude = longitude;

            int sp = (body.charAt(3) - 28) * 10;
            int cr = body.charAt(4) - 28;
            int quotient = cr / 10;
            cr = cr - quotient * 10;
            cr = cr * 100 + (body.charAt(5) - 28);
            sp += quotient;
            if (sp >= 800) {
                sp -= 800;
            }
            if (cr >= 400) {
                cr -= 400;
            }
            if (!AprsWeatherParser.isWeatherSymbol(pos.symbolTable, pos.symbol)) {
                pos.speed = sp * 1.852 / 3.6;
                pos.course = cr;
            }

            if (body.length() > 8) {
                pos.comment = body.substring(8);
            }
            if (Double.isNaN(pos.latitude) || Double.isNaN(pos.longitude)) {
                return null;
            }
            return pos;
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            Log.d(TAG, "Mic-E parse failed: " + e.getMessage());
            return null;
        }
    }

    /** Mic-E latitude ambiguity = trailing spaces in the 6-char decoded latitude field (aprslib). */
    private static int countMicELatitudeTrailingSpaces(String six) {
        int n = 0;
        for (int i = six.length() - 1; i >= 0; i--) {
            if (six.charAt(i) == ' ') {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    /**
     * Parse an uncompressed APRS position string.
     * Format: DDMM.MMN/DDDMM.MME (latitude/longitude in degrees-minutes)
     */
    private static AprsPosition parseUncompressedPosition(AprsPosition pos,
                                                          String info,
                                                          int offset) {
        if (info.length() < offset + 19) {
            return null;
        }

        String latStr = info.substring(offset, offset + 8);     // "DDMM.MMN"
        char symTable = info.charAt(offset + 8);                  // Symbol table
        String lonStr = info.substring(offset + 9, offset + 18); // "DDDMM.MME"
        char symChar = info.charAt(offset + 18);                  // Symbol char

        pos.latitude = parseLatitude(latStr);
        pos.longitude = parseLongitude(lonStr);
        pos.symbolTable = symTable;
        pos.symbol = symChar;

        // Parse optional course/speed after position (not on WX '_' symbols; see AprsWeatherParser)
        if (info.length() > offset + 19) {
            String extra = info.substring(offset + 19);
            if (!AprsWeatherParser.isWeatherSymbol(symTable, symChar)) {
                parseCourseSpeed(pos, extra);
            }
            // Look for altitude in comments /A=NNNNNN
            int altIdx = extra.indexOf("/A=");
            if (altIdx >= 0 && extra.length() >= altIdx + 9) {
                try {
                    int altFeet = Integer.parseInt(extra.substring(altIdx + 3, altIdx + 9));
                    pos.altitude = altFeet * 0.3048; // Convert feet to meters
                } catch (NumberFormatException ignored) {
                }
            }
            pos.comment = extra;
        }

        if (Double.isNaN(pos.latitude) || Double.isNaN(pos.longitude)) {
            return null;
        }

        return pos;
    }

    /**
     * Parse APRS latitude: "DDMM.MMN" where N is N/S indicator.
     */
    private static double parseLatitude(String s) {
        try {
            int degrees = Integer.parseInt(s.substring(0, 2));
            double minutes = Double.parseDouble(s.substring(2, 7));
            char ns = s.charAt(7);

            double lat = degrees + minutes / 60.0;
            if (ns == 'S' || ns == 's') {
                lat = -lat;
            }
            return lat;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Parse APRS longitude: "DDDMM.MME" where E is E/W indicator.
     */
    private static double parseLongitude(String s) {
        try {
            int degrees = Integer.parseInt(s.substring(0, 3));
            double minutes = Double.parseDouble(s.substring(3, 8));
            char ew = s.charAt(8);

            double lon = degrees + minutes / 60.0;
            if (ew == 'W' || ew == 'w') {
                lon = -lon;
            }
            return lon;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Parse optional course/speed: "CCC/SSS" (course degrees / speed knots).
     */
    private static void parseCourseSpeed(AprsPosition pos, String extra) {
        if (extra.length() >= 7 && extra.charAt(3) == '/') {
            try {
                int course = Integer.parseInt(extra.substring(0, 3));
                int speedKnots = Integer.parseInt(extra.substring(4, 7));
                pos.course = course;
                pos.speed = speedKnots * 0.514444; // knots to m/s
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * Try to parse an APRS message from an AX.25 frame's info field.
     * Format: ":ADDRESSEE:message text{ID"
     * Returns null if this is not a message packet.
     */
    public static AprsMessage parseMessage(String fromCallsign, String info) {
        if (info == null || info.length() < 12 || info.charAt(0) != ':') {
            return null;
        }

        // Addressee is 9 characters after the initial ':'
        String addressee = info.substring(1, 10).trim();

        // Message text starts after the second ':'
        if (info.charAt(10) != ':') {
            return null;
        }
        String messageText = info.substring(11);

        AprsMessage msg = new AprsMessage();
        msg.fromCallsign = fromCallsign;
        msg.toCallsign = addressee;

        // Check for message ID (after '{')
        int idIdx = messageText.lastIndexOf('{');
        if (idIdx >= 0) {
            msg.messageId = messageText.substring(idIdx + 1);
            msg.message = messageText.substring(0, idIdx);
        } else {
            msg.message = messageText;
        }

        return msg;
    }

    /** Payload bytes for logging (third-party inner uses UTF-8). */
    public static byte[] toUtf8Bytes(String s) {
        if (s == null) {
            return new byte[0];
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
