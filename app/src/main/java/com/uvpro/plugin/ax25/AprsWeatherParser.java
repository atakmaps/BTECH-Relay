package com.uvpro.plugin.ax25;

import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses APRS weather from position comment tails and {@code _} weather symbols.
 * Logic aligned with aprslib {@code parse_weather_data}.
 */
public final class AprsWeatherParser {

    private static final String TAG = "UVPro.APRS.WX";

    private static final double WIND_MPH_TO_MPS = 0.44704;
    private static final double RAIN_HUNDREDTHS_IN_TO_MM = 0.254;

    /** Tagged weather tokens at the start of the comment tail. */
    private static final Pattern WX_TOKEN = Pattern.compile(
            "^([cSgtrpPlLs#]\\d{3}|t-?\\d{2,3}|h\\d{2}|b\\d{5}|s\\.\\d{2}|s\\d\\.\\d)");

    private static final Pattern TIMESTAMP7 = Pattern.compile("^\\d{6}[hz/]");
    /** No wind sensor: {@code .../...} or {@code 000/000} before tagged WX fields. */
    private static final Pattern WIND_FIELD_PREFIX = Pattern.compile(
            "^([0-9]{3}|\\.{3})\\s*/\\s*([0-9]{3}|\\.{3})");

    private AprsWeatherParser() {
    }

    /**
     * APRS weather-station symbols: {@code _} (home WX) and {@code W} (NWS / calibrated).
     * PANC and many airport METAR feeds use {@code W} on the primary table.
     */
    public static boolean isWeatherSymbol(char symbolTable, char symbol) {
        return symbol == '_' || symbol == 'W';
    }

    public static boolean looksLikeWeatherComment(String comment) {
        if (comment == null || comment.isEmpty()) {
            return false;
        }
        String s = comment.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s.contains("t") && (s.contains("c") || s.contains("g") || s.contains("h")
                || s.contains("b") || s.matches(".*[csgtrpPhbL]\\d{2,}.*"));
    }

    /**
     * Parse weather from a position report and update {@link AprsParser.AprsPosition}.
     */
    public static void enrichPosition(AprsParser.AprsPosition pos) {
        if (pos == null) {
            return;
        }
        String body = pos.comment != null ? pos.comment.trim() : "";
        boolean wxSymbol = isWeatherSymbol(pos.symbolTable, pos.symbol);
        if (!wxSymbol && !looksLikeWeatherComment(body)) {
            return;
        }

        AprsWeather wx = parseWeatherData(body, pos);
        if (wx != null && wx.hasAnyReading()) {
            pos.weather = wx;
            pos.comment = wx.leftoverComment != null ? wx.leftoverComment.trim() : "";
            Log.d(TAG, "Parsed WX for " + pos.callsign + ": " + wx.formatOneLine()
                    + " raw=\"" + body + "\"");
        } else if (wxSymbol || looksLikeWeatherComment(body)) {
            Log.d(TAG, "WX parse empty for " + pos.callsign + " raw=\"" + body + "\"");
        }
        if (wxSymbol || pos.weather != null) {
            clearVehicleMotion(pos);
        }
    }

    /**
     * WX/fixed stations must not expose wind or compressed-extension bytes as vehicle track.
     */
    public static void clearVehicleMotion(AprsParser.AprsPosition pos) {
        if (pos == null) {
            return;
        }
        pos.course = -1;
        pos.speed = -1;
    }

    public static boolean shouldSuppressVehicleMotion(AprsParser.AprsPosition pos) {
        if (pos == null) {
            return false;
        }
        return isWeatherSymbol(pos.symbolTable, pos.symbol) || pos.weather != null;
    }

    private static AprsWeather parseWeatherData(String body, AprsParser.AprsPosition pos) {
        if (body == null) {
            body = "";
        }
        body = body.trim();
        if (body.startsWith("/")) {
            body = body.substring(1).trim();
        }
        if (body.length() >= 7 && TIMESTAMP7.matcher(body.substring(0, 7)).matches()) {
            body = body.substring(7).trim();
        }

        AprsWeather wx = new AprsWeather();
        body = stripLeadingWindPlaceholders(body, wx);

        // Remaining legacy "DDD/SSS" → cDDDsSSS for tagged parse (wind only, not vehicle track)
        body = body.replaceFirst("^([0-9]{3})/([0-9]{3})", "c$1S$2");
        int firstS = body.indexOf('s');
        if (firstS >= 0) {
            body = body.substring(0, firstS) + 'S' + body.substring(firstS + 1);
        }

        String remaining = body;
        int parsed = 0;
        while (remaining.length() > 0) {
            if (remaining.charAt(0) == '/') {
                remaining = remaining.substring(1).trim();
                continue;
            }
            Matcher m = WX_TOKEN.matcher(remaining);
            if (!m.find()) {
                break;
            }
            String token = m.group(1);
            applyToken(wx, token);
            remaining = remaining.substring(m.end()).trim();
            parsed++;
            if (parsed > 32) {
                break;
            }
        }
        wx.leftoverComment = remaining.trim();
        return wx.hasAnyReading() ? wx : null;
    }

    private static void applyToken(AprsWeather wx, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        char tag = token.charAt(0);
        String val = token.substring(1).trim();
        try {
            switch (tag) {
                case 'c':
                    wx.windDirDegrees = parseIntField(val);
                    break;
                case 'S':
                    wx.windSpeedMps = parseIntField(val) * WIND_MPH_TO_MPS;
                    break;
                case 'g':
                    wx.windGustMps = parseIntField(val) * WIND_MPH_TO_MPS;
                    break;
                case 't': {
                    int tF = Integer.parseInt(val.replace(" ", ""));
                    wx.tempCelsius = (tF - 32) / 1.8;
                    break;
                }
                case 'r':
                    wx.rain1hMm = parseIntField(val) * RAIN_HUNDREDTHS_IN_TO_MM;
                    break;
                case 'p':
                    wx.rain24hMm = parseIntField(val) * RAIN_HUNDREDTHS_IN_TO_MM;
                    break;
                case 'P':
                    wx.rainSinceMidnightMm = parseIntField(val) * RAIN_HUNDREDTHS_IN_TO_MM;
                    break;
                case 'h': {
                    int h = parseIntField(val);
                    wx.humidityPercent = (h == 0) ? 100 : h;
                    break;
                }
                case 'b':
                    wx.pressureHpa = (double) Float.parseFloat(val.replace(" ", "")) / 10.0;
                    break;
                case 'L':
                    wx.luminosityWpm2 = parseIntField(val);
                    break;
                case 'l':
                    wx.luminosityWpm2 = parseIntField(val) + 1000;
                    break;
                case 's':
                    wx.snowMm = (double) Float.parseFloat(val.replace(" ", "")) * 25.4;
                    break;
                case '#':
                    wx.rainRaw = parseIntField(val);
                    break;
                default:
                    break;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static int parseIntField(String val) {
        return Integer.parseInt(val.replace(" ", "").replace(".", "0"));
    }

    /**
     * Leading WX wind field: {@code .../...} = no sensors, {@code 230/012} = dir/speed (mph).
     * Must not be written to {@link AprsParser.AprsPosition#course}/{@link AprsParser.AprsPosition#speed}.
     */
    private static String stripLeadingWindPlaceholders(String body, AprsWeather wx) {
        String s = body;
        for (int i = 0; i < 2; i++) {
            Matcher m = WIND_FIELD_PREFIX.matcher(s);
            if (!m.find() || m.start() != 0) {
                break;
            }
            String dirField = m.group(1);
            String spdField = m.group(2);
            boolean dotsDir = dirField.chars().allMatch(c -> c == '.');
            boolean dotsSpd = spdField.chars().allMatch(c -> c == '.');
            if (dotsDir && dotsSpd) {
                wx.windSensorsMissing = true;
            } else if (dirField.matches("\\d{3}") && spdField.matches("\\d{3}")) {
                try {
                    int dir = Integer.parseInt(dirField);
                    int mph = Integer.parseInt(spdField);
                    if (dir >= 0 && dir <= 360) {
                        wx.windDirDegrees = dir;
                    }
                    wx.windSpeedMps = mph * WIND_MPH_TO_MPS;
                } catch (NumberFormatException ignored) {
                }
            }
            s = s.substring(m.end()).trim();
        }
        return s;
    }
}
