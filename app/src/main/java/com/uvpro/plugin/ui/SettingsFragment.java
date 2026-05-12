package com.uvpro.plugin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.gui.PanPreference;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.PluginPreferenceFragment;

/**
 * Settings screen for the UVPro plugin.
 *
 * Provides configuration for:
 * - Callsign
 * - Beacon interval
 * - Chat relay toggle
 * - CoT relay toggle
 * - Auto-reconnect toggle
 */
public class SettingsFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    /** Key registered with {@code ToolsPreferenceFragment} in {@link com.uvpro.plugin.UVProMapComponent}. */
    public static final String TOOL_SETTINGS_KEY = "uvproPreference";

    public static final String PREF_BEACON_INTERVAL = "uvpro_beacon_interval";
    public static final String PREF_AUTO_RECONNECT = "uvpro_auto_reconnect";
    public static final String PREF_ENCRYPTION_ENABLED = "uvpro_encryption_enabled";
    public static final String PREF_ENCRYPTION_PASSPHRASE = "uvpro_encryption_passphrase";
    public static final String PREF_RETRY_INTERVAL_MIN = "uvpro_retry_interval_min";
    public static final String PREF_RETRY_MAX = "uvpro_retry_max";
    public static final String PREF_SA_RELAY_ENABLED = "uvpro_sa_relay_enabled";
    public static final String PREF_PING_REPLY_ENABLED = "uvpro_ping_reply_enabled";

    /** Injected after inflate — some ATAK builds omit custom Pan* prefs from XML. */
    public static final String KEY_BLUETOOTH_DEVICES = "uvpro_bluetooth_devices";
    public static final String KEY_CAT_RADIO = "uvpro_cat_radio";

    public static final String DEFAULT_BEACON_INTERVAL = "300";
    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final String DEFAULT_RETRY_INTERVAL_MIN = "2";
    public static final String DEFAULT_RETRY_MAX = "3";

    private static Context staticPluginContext;

    /**
     * Zero-arg constructor required by Android fragment system.
     * Only valid after the 1-arg constructor has been called once.
     */
    public SettingsFragment() {
        super(staticPluginContext, getResourceId());
    }

    public SettingsFragment(final Context pluginContext) {
        super(pluginContext, getResourceId());
        staticPluginContext = pluginContext;
    }

    private static int getResourceId() {
        if (staticPluginContext == null) return 0;
        return staticPluginContext.getResources().getIdentifier(
                "preferences", "xml", staticPluginContext.getPackageName());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureBluetoothDevicesPreference();
    }

    /**
     * Ensures "Bluetooth Devices" appears under Radio Settings. Preference is added
     * programmatically so it survives ATAK preference-inflation quirks.
     */
    private void ensureBluetoothDevicesPreference() {
        android.util.Log.d("UVPro.Settings", "ensureBluetoothDevicesPreference called");
        Preference existing = findPreference(KEY_BLUETOOTH_DEVICES);
        if (existing != null) {
            android.util.Log.d("UVPro.Settings", "bluetooth pref already exists, wiring click");
            wireBluetoothDevicesClick(existing);
            return;
        }

        // Try the radio category first, fall back to the bluetooth category
        PreferenceCategory radio = (PreferenceCategory) findPreference(KEY_CAT_RADIO);
        android.util.Log.d("UVPro.Settings", "uvpro_cat_radio lookup: " + radio);
        if (radio == null) {
            radio = (PreferenceCategory) findPreference("uvpro_cat_bluetooth");
            android.util.Log.d("UVPro.Settings", "uvpro_cat_bluetooth fallback: " + radio);
        }
        if (radio == null) {
            // Last resort: add directly to the root preference screen
            android.preference.PreferenceScreen root = getPreferenceScreen();
            android.util.Log.d("UVPro.Settings", "preferenceScreen: " + root
                    + (root != null ? ", count=" + root.getPreferenceCount() : ""));
            if (root == null) return;
            Context ctx = getContext();
            if (ctx == null) ctx = staticPluginContext;
            if (ctx == null) return;
            try {
                PanPreference p = new PanPreference(ctx);
                p.setKey(KEY_BLUETOOTH_DEVICES);
                p.setTitle("Bluetooth Devices");
                p.setSummary("Radios you have connected — rename, favorite, delete");
                p.setPersistent(false);
                p.setSelectable(true);
                root.addPreference(p);
                wireBluetoothDevicesClick(p);
                android.util.Log.d("UVPro.Settings", "added bluetooth pref to root screen");
            } catch (Exception e) {
                android.util.Log.e("UVPro.Settings", "Could not add bluetooth pref to root", e);
            }
            return;
        }

        Context ctx = getContext();
        if (ctx == null) ctx = staticPluginContext;
        if (ctx == null) {
            android.util.Log.e("UVPro.Settings", "context is null, cannot create PanPreference");
            return;
        }
        try {
            PanPreference p = new PanPreference(ctx);
            p.setKey(KEY_BLUETOOTH_DEVICES);
            p.setTitle("Bluetooth Devices");
            p.setSummary("Radios you have connected — rename, favorite, delete");
            p.setPersistent(false);
            p.setSelectable(true);
            p.setOrder(-1000);
            radio.addPreference(p);
            wireBluetoothDevicesClick(p);
            android.util.Log.d("UVPro.Settings", "added bluetooth pref to category: " + radio.getKey());
        } catch (Exception e) {
            android.util.Log.e("UVPro.Settings",
                    "Could not add Bluetooth Devices preference", e);
        }
    }

    private void wireBluetoothDevicesClick(Preference bt) {
        bt.setOnPreferenceClickListener(preference -> {
            Context c = getActivity() != null ? getActivity() : getContext();
            try {
                if (c == null && MapView.getMapView() != null) {
                    c = MapView.getMapView().getContext();
                }
            } catch (Exception ignored) {
            }
            if (c != null) {
                BluetoothDevicesManagement.show(c, null);
            }
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        updateSummaries();
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs,
                                          String key) {
        updateSummaries();
    }

    private void updateSummaries() {
        SharedPreferences prefs =
                getPreferenceManager().getSharedPreferences();

        Preference beaconPref = findPreference(PREF_BEACON_INTERVAL);
        if (beaconPref != null) {
            String interval = prefs.getString(
                    PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
            beaconPref.setSummary("Every " + interval + " seconds");
        }

        Preference retryIntervalPref = findPreference(PREF_RETRY_INTERVAL_MIN);
        if (retryIntervalPref != null) {
            String mins = prefs.getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
            retryIntervalPref.setSummary("Retry after " + mins + " minute(s) with no ACK");
        }

        Preference retryMaxPref = findPreference(PREF_RETRY_MAX);
        if (retryMaxPref != null) {
            String max = prefs.getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
            retryMaxPref.setSummary("Up to " + max + " retransmit attempt(s) before failure");
        }

        Preference saRelayPref = findPreference(PREF_SA_RELAY_ENABLED);
        if (saRelayPref != null) {
            boolean on = prefs.getBoolean(PREF_SA_RELAY_ENABLED, false);
            saRelayPref.setSummary(on
                    ? "On — network PLI/markers/routes relayed over radio when connected"
                    : "Off");
        }
    }

    @Override
    public String getSubTitle() {
        return "UV-PRO Settings";
    }

    /**
     * Convenience: Get a preference value from any context.
     */
    /**
     * Get the ATAK-process SharedPreferences (uses ATAK context, not plugin context).
     */
    private static android.content.SharedPreferences getPrefs(Context context) {
        // Plugin context can't write to its own shared_prefs dir because it
        // runs inside ATAK's process. Always use ATAK's context.
        Context ctx = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String getCallsign(Context context) {
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null && mv.getSelfMarker() != null) {
                return mv.getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception e) {
        }
        return "UNKNOWN";
    }

    public static int getBeaconIntervalSec(Context context) {
        String val = getPrefs(context)
                .getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public static long getRetryIntervalMs(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
        try {
            return Long.parseLong(val) * 60_000L;
        } catch (NumberFormatException e) {
            return 2 * 60_000L;
        }
    }

    public static int getMaxChatRetries(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
        try {
            return Math.max(1, Integer.parseInt(val));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    public static boolean isAutoReconnectEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT);
    }

    /**
     * Use ATAK's team color ("locationTeam") rather than a plugin-managed setting, so
     * radio contacts match the operator's configured team consistently.
     */
    public static String getAtakTeamColor(Context context) {
        try {
            String team = ChatManagerMapComponent.getTeamName();
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        try {
            com.atakmap.android.preference.AtakPreferences prefs =
                    com.atakmap.android.preference.AtakPreferences.getInstance(
                            com.atakmap.android.maps.MapView.getMapView() != null
                                    ? com.atakmap.android.maps.MapView.getMapView().getContext()
                                    : context);
            String team = prefs.get("locationTeam", "Cyan");
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    public static boolean isSaRelayEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_SA_RELAY_ENABLED, false);
    }

    public static boolean isPingReplyEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_PING_REPLY_ENABLED, true);
    }

    public static boolean isEncryptionEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ENCRYPTION_ENABLED, false);
    }

    public static String getEncryptionPassphrase(Context context) {
        return getPrefs(context)
                .getString(PREF_ENCRYPTION_PASSPHRASE, "");
    }
}
