package com.uvpro.plugin.util;

import com.atakmap.coremap.conversions.CoordinateFormat;
import com.atakmap.coremap.conversions.CoordinateFormatUtilities;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Locale;

/** Formats coordinates for operator-facing UI. */
public final class CoordinateDisplay {

    private CoordinateDisplay() {
    }

    /** WGS84 position as ATAK MGRS string, with decimal-degree fallback. */
    public static String formatMgrs(double latitude, double longitude) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return "MGRS: unavailable";
        }
        try {
            GeoPoint gp = new GeoPoint(latitude, longitude);
            String mgrs = CoordinateFormatUtilities.formatToString(gp, CoordinateFormat.MGRS);
            if (mgrs != null && !mgrs.trim().isEmpty()) {
                return mgrs.trim();
            }
        } catch (Exception ignored) {
        }
        return String.format(Locale.US, "%.5f°, %.5f°", latitude, longitude);
    }
}
