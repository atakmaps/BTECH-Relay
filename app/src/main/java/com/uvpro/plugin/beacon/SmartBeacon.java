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
    public static final String KEY_TURN_THRESHOLD = "uvpro_smart_beacon_turn_threshold";
    public static final String KEY_TURN_SLOPE     = "uvpro_smart_beacon_turn_slope";

    // Default parameter values (based on the Smart Beacon algorithm)
    // Default OFF so fixed beacon interval remains authoritative unless explicitly enabled.
    public static final boolean DEFAULT_ENABLED        = false;
    public static final int     DEFAULT_LOW_SPEED      = 5;    // mph
    public static final int     DEFAULT_HIGH_SPEED     = 60;   // mph
    public static final int     DEFAULT_SLOW_RATE      = 180;  // seconds (3 min)
    public static final int     DEFAULT_FAST_RATE      = 60;   // seconds (1 min)
    public static final int     DEFAULT_TURN_THRESHOLD = 28;   // degrees
    public static final int     DEFAULT_TURN_SLOPE     = 26;   // dimensionless slope factor

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
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getInt(KEY_LOW_SPEED, DEFAULT_LOW_SPEED);
    }

    public static int getHighSpeed(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getInt(KEY_HIGH_SPEED, DEFAULT_HIGH_SPEED);
    }

    public static int getSlowRate(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getInt(KEY_SLOW_RATE, DEFAULT_SLOW_RATE);
    }

    public static int getFastRate(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getInt(KEY_FAST_RATE, DEFAULT_FAST_RATE);
    }

    public static int getTurnThreshold(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getInt(KEY_TURN_THRESHOLD, DEFAULT_TURN_THRESHOLD);
    }

    public static int getTurnSlope(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getInt(KEY_TURN_SLOPE, DEFAULT_TURN_SLOPE);
    }

    public static void saveAll(Context ctx, int lowSpeed, int highSpeed,
            int slowRate, int fastRate, int turnThreshold, int turnSlope) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putInt(KEY_LOW_SPEED,      lowSpeed)
                .putInt(KEY_HIGH_SPEED,     highSpeed)
                .putInt(KEY_SLOW_RATE,      slowRate)
                .putInt(KEY_FAST_RATE,      fastRate)
                .putInt(KEY_TURN_THRESHOLD, turnThreshold)
                .putInt(KEY_TURN_SLOPE,     turnSlope)
                .apply();
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
        int turnThreshold = getTurnThreshold(ctx);
        int turnSlope     = getTurnSlope(ctx);

        // --- 1. Calculate the speed-proportional beacon rate ---
        int beaconRate;
        if (speedMph <= lowSpeed) {
            beaconRate = slowRate;
        } else if (speedMph >= highSpeed) {
            beaconRate = fastRate;
        } else {
            // Linear interpolation between fast and slow rates
            double fraction = (speedMph - lowSpeed) / (double)(highSpeed - lowSpeed);
            beaconRate = (int)(slowRate - fraction * (slowRate - fastRate));
        }
        beaconRate = Math.max(beaconRate, fastRate);

        // --- 2. Check rate-based trigger ---
        if (elapsedSec >= beaconRate) {
            return true;
        }

        // --- 3. Check turn-based trigger ---
        if (lastBeaconHeading >= 0 && speedMph > lowSpeed) {
            double delta = Math.abs(headingDeg - lastBeaconHeading);
            if (delta > 180) delta = 360 - delta; // shortest arc

            // Effective threshold decreases with speed (sharper turns at low speed trigger later)
            double effectiveThreshold = turnThreshold + turnSlope / Math.max(speedMph, 1.0);
            if (delta > effectiveThreshold && elapsedSec >= fastRate) {
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
}
