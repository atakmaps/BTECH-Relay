package com.uvpro.plugin.aprs;

import com.uvpro.plugin.ax25.AprsParser;
import com.uvpro.plugin.ax25.AprsSymbolMapper;
import com.uvpro.plugin.ax25.AprsWeather;
import com.uvpro.plugin.ax25.AprsWeatherParser;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.util.CoordinateDisplay;

import java.util.Locale;

/**
 * Formats inbound APRS fields for the APRS details panel (not generic CoT point UI).
 */
public final class AprsInfoFormatter {

    private AprsInfoFormatter() {
    }

    public static String formatPosition(String displayCall,
                                        AprsParser.AprsPosition pos) {
        if (pos == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append(displayCall != null ? displayCall.trim() : pos.callsign);
        sb.append("\n\n");

        boolean wxStation = AprsWeatherParser.isWeatherSymbol(pos.symbolTable, pos.symbol);
        if (pos.symbolTable != 0 && pos.symbol != 0) {
            sb.append(wxStation ? "APRS weather station\n" : "APRS symbol: '");
            if (!wxStation) {
                sb.append(pos.symbolTable).append("' '").append(pos.symbol).append('\'');
            } else {
                sb.append("  Symbol: weather (").append(pos.symbolTable).append('/')
                        .append(pos.symbol).append(")\n");
            }
            String icon = AprsSymbolMapper.iconsetPath(pos.symbolTable, pos.symbol);
            if (icon != null) {
                int slash = icon.lastIndexOf('/');
                if (slash >= 0) {
                    sb.append("  Icon: ").append(icon.substring(slash + 1)).append('\n');
                }
            }
            if (!wxStation) {
                sb.append('\n');
            }
        }

        AprsWeather wx = pos.weather;
        if (wx != null && wx.hasAnyReading()) {
            sb.append("\nWeather report\n");
            sb.append(wx.formatForPanel());
        }

        sb.append("\nPosition\n");
        sb.append("  MGRS: ").append(CoordinateDisplay.formatMgrs(pos.latitude, pos.longitude))
                .append('\n');
        if (pos.altitude >= 0 && pos.altitude < 99999) {
            sb.append(String.format(Locale.US, "  Altitude: %.0f m\n", pos.altitude));
        }
        boolean suppressMotion = wxStation
                || (wx != null && wx.hasAnyReading())
                || AprsWeatherParser.shouldSuppressVehicleMotion(pos);
        if (!suppressMotion && pos.course >= 0) {
            sb.append(String.format(Locale.US, "  Course: %.0f°\n", pos.course));
        }
        if (!suppressMotion && pos.speed >= 0) {
            sb.append(String.format(Locale.US, "  Speed: %.1f m/s (%.1f mph)\n",
                    pos.speed, pos.speed * 2.23694));
        }

        String comment = pos.comment != null ? pos.comment.trim() : "";
        if (!comment.isEmpty()) {
            sb.append("\nAdditional comment\n  ");
            sb.append(comment.replace("\r", " ").replace("\n", " "));
            sb.append('\n');
        }

        sb.append("\nSource: APRS over RF (UV-PRO)");
        return sb.toString();
    }

    public static String withTelemetry(String existing, AprsParser.AprsTelemetry telem) {
        if (telem == null) {
            return existing != null ? existing : "";
        }
        String line = telem.formatSummary();
        if (existing == null || existing.isEmpty()) {
            return "Telemetry\n  " + line + "\n";
        }
        if (existing.contains(line)) {
            return existing;
        }
        return existing + "\n\nTelemetry (latest)\n  " + line + "\n";
    }

    public static String withContactStats(String body, RadioContact contact) {
        if (contact == null) {
            return body;
        }
        StringBuilder sb = new StringBuilder(body != null ? body : "");
        sb.append("\n\nActivity\n");
        sb.append("  Packets: ").append(contact.getPacketCount()).append('\n');
        sb.append("  Last heard: ").append(contact.getAgeSec()).append(" s ago\n");
        sb.append("  Status: ").append(contact.getStatus()).append('\n');
        return sb.toString();
    }
}
