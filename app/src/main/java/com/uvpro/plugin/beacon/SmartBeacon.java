package com.uvpro.plugin.beacon;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Smart Beaconing algorithm for AX.25 position reporting via UV-PRO radio.
 * Dynamically adjusts the beacon rate based on speed and heading change —
 * similar in concept to the well-known Smart Beacon algorithm but applied
 * to UV-PRO AX.25 packets, not APRS.
 *
 * Dynamically adjusts the beacon rate based on speed and heading change:
 *  - Fast/turning  → beacon more frequently (down to fastRate)
 *  - Slow/straight → beacon less frequently (up to slowRate)
 *
 * Algorithm follows the original HamHUD Smart Beaconing specification.
 */
public class SmartBeacon {

    // SharedPreferences keys
    public static final String KEY_ENABLED        = "uvpro_smart_beacon_enabled";
    public static final String KEY_LOW_SPEED      = "uvpro_smart_beacon_low_speed";
    public static final String KEY_HIGH_SPEED     = "uvpro_smart_beacon_high_speed";
    public static final String KEY_SLOW_RATE      = "uvpro_smart_beacon_slow_rate";
    public static final String KEY_FAST_RATE      = "uvpro_smart_beacon_fast_rate";
    public static final String KEY_MIN_TURN_TIME  = "uvpro_smart_beacon_min_turn_time";
    public static final String KEY_TURN_THRESHOLD = "uvpro_smart_beacon_turn_threshold";
    public static final String KEY_TURN_SLOPE     = "uvpro_smart_beacon_turn_slope";

    // Default parameter values (based on the Smart Beacon algorithm)
    // Default OFF so fixed beacon interval remains authoritative unless explicitly enabled.
    public static final boolean DEFAULT_ENABLED        = false;
    public static final int     DEFAULT_LOW_SPEED      = 5;    // mph
    public static final int     DEFAULT_HIGH_SPEED     = 60;   // mph
    public static final int     DEFAULT_SLOW_RATE      = 1800; // seconds (30 min)
    public static final int     DEFAULT_FAST_RATE      = 180;  // seconds (3 min)
    public static final int     DEFAULT_MIN_TURN_TIME  = 15;   // seconds
    public static final int     DEFAULT_TURN_THRESHOLD = 30;   // degrees
    public static final int     DEFAULT_TURN_SLOPE     = 255;  // speed*angle slope

    private long   lastBeaconTimeMs   = 0;
    private double lastBeaconHeading  = -1;

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    public static boolean isEnabled(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getLowSpeed(Context ctx) {
        return getIntCompat(ctx, KEY_LOW_SPEED, DEFAULT_LOW_SPEED);
    }

    public static int getHighSpeed(Context ctx) {
        return getIntCompat(ctx, KEY_HIGH_SPEED, DEFAULT_HIGH_SPEED);
    }

    public static int getSlowRate(Context ctx) {
        return getIntCompat(ctx, KEY_SLOW_RATE, DEFAULT_SLOW_RATE);
    }

    public static int getFastRate(Context ctx) {
        return getIntCompat(ctx, KEY_FAST_RATE, DEFAULT_FAST_RATE);
    }

    public static int getMinTurnTime(Context ctx) {
        return getIntCompat(ctx, KEY_MIN_TURN_TIME, DEFAULT_MIN_TURN_TIME);
    }

    public static int getTurnThreshold(Context ctx) {
        return getIntCompat(ctx, KEY_TURN_THRESHOLD, DEFAULT_TURN_THRESHOLD);
    }

    public static int getTurnSlope(Context ctx) {
        return getIntCompat(ctx, KEY_TURN_SLOPE, DEFAULT_TURN_SLOPE);
    }

    public static void saveAll(Context ctx, int lowSpeed, int highSpeed,
            int slowRate, int fastRate, int minTurnTime, int turnThreshold, int turnSlope) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putInt(KEY_LOW_SPEED,      lowSpeed)
                .putInt(KEY_HIGH_SPEED,     highSpeed)
                .putInt(KEY_SLOW_RATE,      slowRate)
                .putInt(KEY_FAST_RATE,      fastRate)
                .putInt(KEY_MIN_TURN_TIME,  minTurnTime)
                .putInt(KEY_TURN_THRESHOLD, turnThreshold)
                .putInt(KEY_TURN_SLOPE,     turnSlope)
                .apply();
    }

    private static int getIntCompat(Context ctx, String key, int fallback) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            try {
                String s = prefs.getString(key, null);
                if (s == null) {
                    return fallback;
                }
                int v = Integer.parseInt(s.trim());
                prefs.edit().putInt(key, v).apply();
                return v;
            } catch (Exception ignored2) {
                return fallback;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core algorithm
    // -------------------------------------------------------------------------

    /**
     * Evaluate whether a beacon should be sent right now.
     *
     * @param ctx            application context (for reading prefs)
     * @param speedMph       current ground speed in mph
     * @param headingDeg     current heading in degrees (0-360)
     * @return true if a beacon should be transmitted
     */
    public boolean shouldBeacon(Context ctx, double speedMph, double headingDeg) {
        long nowMs = System.currentTimeMillis();
        long elapsedSec = (nowMs - lastBeaconTimeMs) / 1000L;

        int lowSpeed      = getLowSpeed(ctx);
        int highSpeed     = getHighSpeed(ctx);
        int slowRate      = getSlowRate(ctx);
        int fastRate      = getFastRate(ctx);
        int minTurnTime   = Math.max(1, getMinTurnTime(ctx));
        int turnThreshold = Math.max(1, getTurnThreshold(ctx));
        int turnSlope     = Math.max(0, getTurnSlope(ctx));
        lowSpeed = Math.max(1, lowSpeed);
        highSpeed = Math.max(lowSpeed + 1, highSpeed);
        fastRate = Math.max(1, fastRate);
        slowRate = Math.max(fastRate + 1, slowRate);

        // APRS SmartBeaconing rate model:
        // - <= slow speed: slow rate
        // - >= fast speed: fast rate
        // - between: proportional spacing based on distance-per-position
        int beaconRate;
        if (speedMph <= lowSpeed) {
            beaconRate = slowRate;
        } else if (speedMph >= highSpeed) {
            beaconRate = fastRate;
        } else {
            beaconRate = (int) Math.round((fastRate * (double) highSpeed) / speedMph);
        }
        beaconRate = Math.max(fastRate, Math.min(slowRate, beaconRate));

        if (elapsedSec >= beaconRate) {
            return true;
        }

        if (lastBeaconHeading >= 0 && speedMph > lowSpeed) {
            double delta = Math.abs(headingDeg - lastBeaconHeading);
            if (delta > 180) delta = 360 - delta; // shortest arc

            double effectiveThreshold = turnThreshold + turnSlope / Math.max(speedMph, 1.0);
            if (delta > effectiveThreshold && elapsedSec >= minTurnTime) {
                return true;
            }
        }

        return false;
    }

    /**
     * Call this immediately after sending a beacon to record state.
     *
     * @param headingDeg current heading in degrees
     */
    public void recordBeacon(double headingDeg) {
        lastBeaconTimeMs  = System.currentTimeMillis();
        lastBeaconHeading = headingDeg;
    }

    /** Force next check to trigger a beacon (e.g. on startup or reconnect). */
    public void reset() {
        lastBeaconTimeMs  = 0;
        lastBeaconHeading = -1;
    }

    /**
     * Recommended scheduler cadence for SmartBeacon checks.
     * Uses the tighter of fast-rate and min-turn-time so we do not miss turn triggers.
     */
    public static int getRecommendedCheckIntervalSec(Context ctx) {
        int fastRate = Math.max(1, getFastRate(ctx));
        int minTurnTime = Math.max(1, getMinTurnTime(ctx));
        return Math.max(1, Math.min(fastRate, minTurnTime));
    }
}
