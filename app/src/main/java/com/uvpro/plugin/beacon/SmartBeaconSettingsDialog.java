package com.uvpro.plugin.beacon;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Dialog for editing Smart Beacon parameters.
 */
public class SmartBeaconSettingsDialog {

    public static void show(Context ctx, Runnable onSaved) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);
        scroll.addView(layout);

        EditText etLowSpeed      = addField(ctx, layout, "Low Speed Threshold (mph)",
                "Below this speed the slowest rate is used.",
                String.valueOf(SmartBeacon.getLowSpeed(ctx)));

        EditText etHighSpeed     = addField(ctx, layout, "High Speed Threshold (mph)",
                "Above this speed the fastest rate is used.",
                String.valueOf(SmartBeacon.getHighSpeed(ctx)));

        EditText etSlowRate      = addField(ctx, layout, "Slow Rate (seconds)",
                "Max time between beacons when slow or stopped.",
                String.valueOf(SmartBeacon.getSlowRate(ctx)));

        EditText etFastRate      = addField(ctx, layout, "Fast Rate (seconds)",
                "Min time between beacons when moving fast.",
                String.valueOf(SmartBeacon.getFastRate(ctx)));

        EditText etMinTurnTime   = addField(ctx, layout, "Min Turn Time (seconds)",
                "Minimum delay between corner-pegging beacons.",
                String.valueOf(SmartBeacon.getMinTurnTime(ctx)));

        EditText etTurnThreshold = addField(ctx, layout, "Turn Threshold (degrees)",
                "Heading change needed to trigger an early beacon.",
                String.valueOf(SmartBeacon.getTurnThreshold(ctx)));

        EditText etTurnSlope     = addField(ctx, layout, "Turn Slope",
                "Scales turn sensitivity with speed (higher = less sensitive at low speed).",
                String.valueOf(SmartBeacon.getTurnSlope(ctx)));

        new AlertDialog.Builder(ctx)
                .setTitle("Smart Beacon Settings")
                .setView(scroll)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int lowSpeed      = parseInt(etLowSpeed,      SmartBeacon.DEFAULT_LOW_SPEED);
                        int highSpeed     = parseInt(etHighSpeed,     SmartBeacon.DEFAULT_HIGH_SPEED);
                        int slowRate      = parseInt(etSlowRate,      SmartBeacon.DEFAULT_SLOW_RATE);
                        int fastRate      = parseInt(etFastRate,      SmartBeacon.DEFAULT_FAST_RATE);
                        int minTurnTime   = parseInt(etMinTurnTime,   SmartBeacon.DEFAULT_MIN_TURN_TIME);
                        int turnThreshold = parseInt(etTurnThreshold, SmartBeacon.DEFAULT_TURN_THRESHOLD);
                        int turnSlope     = parseInt(etTurnSlope,     SmartBeacon.DEFAULT_TURN_SLOPE);

                        // Basic validation
                        if (highSpeed <= lowSpeed) highSpeed = lowSpeed + 10;
                        if (fastRate >= slowRate)  fastRate  = Math.max(1, slowRate / 2);
                        fastRate  = Math.max(1, fastRate);
                        slowRate  = Math.max(fastRate + 1, slowRate);
                        minTurnTime = Math.max(1, minTurnTime);
                        turnThreshold = Math.max(1, turnThreshold);
                        turnSlope = Math.max(0, turnSlope);

                        SmartBeacon.saveAll(ctx, lowSpeed, highSpeed,
                                slowRate, fastRate, minTurnTime, turnThreshold, turnSlope);

                        if (onSaved != null) onSaved.run();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Defaults", (d, w) -> {
                    SmartBeacon.saveAll(ctx,
                            SmartBeacon.DEFAULT_LOW_SPEED,
                            SmartBeacon.DEFAULT_HIGH_SPEED,
                            SmartBeacon.DEFAULT_SLOW_RATE,
                            SmartBeacon.DEFAULT_FAST_RATE,
                            SmartBeacon.DEFAULT_MIN_TURN_TIME,
                            SmartBeacon.DEFAULT_TURN_THRESHOLD,
                            SmartBeacon.DEFAULT_TURN_SLOPE);
                    if (onSaved != null) onSaved.run();
                    // Re-open so the user sees the restored values
                    show(ctx, onSaved);
                })
                .show();
    }

    private static EditText addField(Context ctx, LinearLayout parent,
            String label, String hint, String value) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setPadding(0, 20, 0, 2);
        parent.addView(tv);

        TextView tvHint = new TextView(ctx);
        tvHint.setText(hint);
        tvHint.setTextColor(0xFFAAAAAA);
        tvHint.setTextSize(11);
        parent.addView(tvHint);

        EditText et = new EditText(ctx);
        et.setText(value);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextColor(0xFFFFFFFF);
        et.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 8);
        et.setLayoutParams(lp);
        parent.addView(et);
        return et;
    }

    private static int parseInt(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
