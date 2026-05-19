package com.uvpro.plugin.ax25;

import java.util.Locale;

/**
 * Decoded APRS weather fields (APRS101 / aprslib {@code parse_weather_data}).
 */
public final class AprsWeather {

    public Integer windDirDegrees;
    /** Sustained wind, m/s. */
    public Double windSpeedMps;
    /** Peak gust (5 min), m/s. */
    public Double windGustMps;
    public Double tempCelsius;
    /** Rain in the last hour, mm. */
    public Double rain1hMm;
    /** Rain in the last 24 hours, mm. */
    public Double rain24hMm;
    /** Rain since midnight, mm. */
    public Double rainSinceMidnightMm;
    /** 1–100%; 100 is encoded as 00. */
    public Integer humidityPercent;
    /** Barometric pressure, hPa. */
    public Double pressureHpa;
    public Integer luminosityWpm2;
    public Double snowMm;
    public Integer rainRaw;
    /** Leading {@code .../...} in packet — station has no wind direction/speed sensors. */
    public boolean windSensorsMissing;
    /** Non-weather tail of the comment, if any. */
    public String leftoverComment;

    public boolean hasWindInfo() {
        return windSensorsMissing || windDirDegrees != null || windSpeedMps != null
                || windGustMps != null;
    }

    public boolean hasAnyReading() {
        return hasWindInfo() || tempCelsius != null || rain1hMm != null || rain24hMm != null
                || rainSinceMidnightMm != null || humidityPercent != null
                || pressureHpa != null || luminosityWpm2 != null || snowMm != null
                || rainRaw != null;
    }

    /** Multi-line block for the APRS details dropdown. */
    public String formatForPanel() {
        if (!hasAnyReading()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        if (hasWindInfo()) {
            if (windSensorsMissing && windDirDegrees == null && windSpeedMps == null) {
                sb.append("  Wind: direction & speed not reported")
                        .append(" (no sensor on station)\n");
            } else {
                sb.append("  Wind: ");
                if (windDirDegrees != null) {
                    sb.append(windDirDegrees).append("°");
                }
                if (windSpeedMps != null) {
                    if (windDirDegrees != null) {
                        sb.append(" at ");
                    }
                    sb.append(String.format(Locale.US, "%.0f mph",
                            windSpeedMps / 0.44704));
                }
                sb.append('\n');
            }
            if (windGustMps != null) {
                sb.append(String.format(Locale.US, "  Gust (5 min peak): %.0f mph\n",
                        windGustMps / 0.44704));
            }
        }
        if (tempCelsius != null) {
            double f = tempCelsius * 1.8 + 32.0;
            sb.append(String.format(Locale.US,
                    "  Temperature: %.0f°F (%.1f°C)\n", f, tempCelsius));
        }
        if (humidityPercent != null) {
            sb.append("  Humidity: ").append(humidityPercent).append("%\n");
        }
        if (pressureHpa != null) {
            sb.append(String.format(Locale.US, "  Pressure: %.1f hPa\n", pressureHpa));
        }
        if (rain1hMm != null) {
            sb.append(String.format(Locale.US, "  Rain (1 h): %.2f in\n",
                    rain1hMm / 25.4));
        }
        if (rain24hMm != null) {
            sb.append(String.format(Locale.US, "  Rain (24 h): %.2f in\n",
                    rain24hMm / 25.4));
        }
        if (rainSinceMidnightMm != null) {
            sb.append(String.format(Locale.US, "  Rain (since midnight): %.2f in\n",
                    rainSinceMidnightMm / 25.4));
        }
        if (snowMm != null) {
            sb.append(String.format(Locale.US, "  Snow (24 h): %.2f in\n",
                    snowMm / 25.4));
        }
        if (luminosityWpm2 != null) {
            sb.append("  Luminosity: ").append(luminosityWpm2).append(" W/m²\n");
        }
        if (rainRaw != null) {
            sb.append("  Raw rain counter: ").append(rainRaw).append('\n');
        }
        return sb.toString();
    }

    /** One-line summary for map remarks. */
    public String formatOneLine() {
        if (!hasAnyReading()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(96);
        if (tempCelsius != null) {
            sb.append(String.format(Locale.US, "%.0f°F",
                    tempCelsius * 1.8 + 32.0));
        }
        if (windSpeedMps != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.format(Locale.US, "%.0f mph wind",
                    windSpeedMps / 0.44704));
            if (windDirDegrees != null) {
                sb.append(" from ").append(windDirDegrees).append('°');
            }
        }
        if (humidityPercent != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(humidityPercent).append("% RH");
        }
        return sb.toString();
    }
}
