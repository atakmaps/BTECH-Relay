package com.uvpro.plugin;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.uvpro.plugin.beacon.SmartBeacon;
import com.atakmap.android.maps.MapView;
import com.atakmap.net.AtakAuthenticationCredentials;
import com.atakmap.net.AtakCertificateDatabase;
import com.atakmap.net.AtakCertificateDatabaseIFace;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.radio.UVProRadioControlManager;
import com.uvpro.plugin.ui.RadioStatusOverlay;
import com.uvpro.plugin.ui.SettingsFragment;

/**
 * UVPro Map Component — the central nervous system of the plugin.
 *
 * Initializes all sub-systems:
 * - Bluetooth connection management
 * - KISS TNC encoder/decoder
 * - CoT bridge (position sharing, marker sync)
 * - Chat bridge (GeoChat relay)
 * - Contact tracker (radio contacts on map)
 * - Packet router (dispatches received data)
 */
public class UVProMapComponent extends DropDownMapComponent {

    private static final String TAG = "UVPro";
    public static final String PLUGIN_PACKAGE = "com.uvpro.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.uvpro.plugin.BEACON_INTERVAL_CHANGED";

    private Context pluginContext;
    private MapView mapView;

    // Sub-systems
    private BtConnectionManager btConnectionManager;
    private PacketRouter packetRouter;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private ContactTracker contactTracker;
    private UVProDropDownReceiver dropDownReceiver;
    private EncryptionManager encryptionManager;
    private UVProRadioControlManager radioControlManager;
    private MapEventDispatcher.MapEventDispatchListener mapItemClickListener;
    private Handler beaconHandler;
    private Runnable beaconRunnable;
    private android.content.BroadcastReceiver beaconIntervalReceiver;
    private final SmartBeacon smartBeacon = new SmartBeacon();

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        // context = plugin context, view.getContext() = map context (use for UI)
        this.pluginContext = context;
        this.mapView = view;

        Log.i(TAG, "UV-PRO plugin initializing...");
        // Defensive: unread badge state is process-local; start clean each time the plugin is loaded.
        try {
            UVProContactHandler.clearAllUnread();
        } catch (Exception ignored) {
        }

        // Read user preferences
        String callsign = "UNKNOWN";
try {
    com.atakmap.android.maps.PointMapItem self = view.getSelfMarker();
    if (self != null) {
        callsign = self.getMetaString("callsign", "UNKNOWN");
    }
} catch (Exception e) {
    android.util.Log.e("BTRelay", "Failed to get ATAK callsign", e);
}

        // Initialize sub-systems in dependency order:
        // 1. CotBridge (needs plugin context + MapView)
        cotBridge = new CotBridge(context, view);
        cotBridge.setLocalCallsign(callsign);

        // GeoChat DM CoT needs local device UID in chatgrp.uid1; resolve on UI thread once
        // so Bluetooth RX thread can inject chat without NULL getDeviceUid().
        view.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cotBridge.refreshCachedLocalDeviceUidForGeoChat();
                } catch (Exception ignored) {
                }
            }
        });

        // 1b. Encryption
        encryptionManager = new EncryptionManager();
        if (SettingsFragment.isEncryptionEnabled(context)) {
            encryptionManager.setSharedSecret(
                    SettingsFragment.getEncryptionPassphrase(context));
        }
        cotBridge.setEncryptionManager(encryptionManager);

        // 2. ChatBridge (needs plugin context + MapView)
        chatBridge = new ChatBridge(context, view);
        chatBridge.setLocalCallsign(callsign);
        chatBridge.setCotBridge(cotBridge);
        cotBridge.setChatBridge(chatBridge);

        // 3. ContactTracker (needs CotBridge for injecting CoT events)
        contactTracker = new ContactTracker(cotBridge);

        // === REGISTER CONTACT HANDLER ===
        try {
            com.atakmap.android.contact.ContactConnectorManager mgr =
                    com.atakmap.android.cot.CotMapComponent.getInstance()
                            .getContactConnectorMgr();

            mgr.addContactHandler(
                    new com.uvpro.plugin.UVProContactHandler(context)
            );

        } catch (Exception e) {
            android.util.Log.e("BTRelay", "Handler registration failed", e);
        }


        // 4. PacketRouter (needs CotBridge, ChatBridge, ContactTracker)
        packetRouter = new PacketRouter(cotBridge, chatBridge, contactTracker);
        packetRouter.setEncryptionManager(encryptionManager);

        // 5. BtConnectionManager (needs context + PacketRouter)
        btConnectionManager = new BtConnectionManager(context, packetRouter);
        radioControlManager = new UVProRadioControlManager(btConnectionManager);
        radioControlManager.start();

        // Status overlay: defer install until after GLWidgetsMapComponent is ready
        view.postDelayed(() -> RadioStatusOverlay.install(context), 2000);
        btConnectionManager.addListener(new BtConnectionManager.ConnectionListener() {
            @Override
            public void onConnected(android.bluetooth.BluetoothDevice device) {
                Log.d(TAG, "StatusOverlay: radio connected");
                RadioStatusOverlay.setConnected(true);
            }
            @Override
            public void onDisconnected(String reason) {
                Log.d(TAG, "StatusOverlay: radio disconnected");
                RadioStatusOverlay.setConnected(false);
            }
            @Override
            public void onError(String error) {}
            @Override
            public void onDeviceFound(android.bluetooth.BluetoothDevice device) {}
        });

        // Auto-connect to last used radio after a short delay (let BT stack settle)
        view.postDelayed(() -> autoConnectLastRadio(context), 4000);

        // Configure ATAK's plugin update server on first install (silent, one-time)
        configureUpdateServer(context);

        // Wire BT manager into bridges so they can transmit
        cotBridge.setBtManager(btConnectionManager);
        chatBridge.setBtManager(btConnectionManager);
        chatBridge.setEncryptionManager(encryptionManager);

        // 6. Create the drop-down UI receiver
        dropDownReceiver = new UVProDropDownReceiver(
                view, pluginContext, btConnectionManager, contactTracker);
        dropDownReceiver.setCotBridge(cotBridge);
        dropDownReceiver.setEncryptionManager(encryptionManager);
        dropDownReceiver.setRadioControlManager(radioControlManager);


        // Wire PacketRouter RX count to dropdown UI
        packetRouter.setPacketCountListener(dropDownReceiver);

        // Register the drop-down with ATAK
        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(UVProDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(dropDownReceiver, filter);

        // Track selected repeater markers from map taps.
        mapItemClickListener = event -> {
            if (event == null || !MapEvent.ITEM_CLICK.equals(event.getType())) {
                return;
            }
            if (event.getItem() == null || radioControlManager == null) {
                return;
            }
            radioControlManager.onMapItemClicked(event.getItem());
        };
        view.getMapEventDispatcher().addMapEventListener(
                MapEvent.ITEM_CLICK, mapItemClickListener);

        // 8. Register settings with ATAK Tools Preferences
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "UV-PRO Settings",
                        "UV-PRO radio bridge configuration",
                        "uvproPreference",
                        context.getResources().getDrawable(
                                context.getResources().getIdentifier(
                                        "ic_uvpro", "drawable",
                                        context.getPackageName()), null),
                        new SettingsFragment(context)));

        // Start background services
        contactTracker.start();
        // Outbound is contact-targeted (+ optional periodic beacon path). Legacy
        // "bridge all PLI/chat" toggles were removed — radio traffic follows ATAK contacts.
        chatBridge.setRelayOutgoing(true);
        chatBridge.startOutgoingRelay();

        // Do not blanket-flood outbound SA/geo over RX; relay when destination is a radio contact.
        cotBridge.setRelayOutgoingSa(false);
        cotBridge.startOutgoingRelay();

        // 9. Start periodic beacon timer
        startBeaconTimer();

        // Listen for runtime preference changes that require rescheduling timers.
        try {
            beaconIntervalReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    if (i == null) return;
                    if (ACTION_BEACON_INTERVAL_CHANGED.equals(i.getAction())) {
                        Log.d(TAG, "Beacon interval changed — rescheduling timer");
                        startBeaconTimer();
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter beaconFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            beaconFilter.addAction(ACTION_BEACON_INTERVAL_CHANGED);
            AtakBroadcast.getInstance()
                    .registerReceiver(beaconIntervalReceiver, beaconFilter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register beacon interval receiver", e);
        }

        Log.i(TAG, "UV-PRO plugin initialized successfully (callsign="
                + callsign + ")");
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        Log.i(TAG, "UV-PRO plugin shutting down...");

        // Stop beacon timer
        if (beaconHandler != null && beaconRunnable != null) {
            beaconHandler.removeCallbacks(beaconRunnable);
        }

        // Unregister settings
        ToolsPreferenceFragment.unregister("uvproPreference");

        // Remove status overlay from the map
        RadioStatusOverlay.uninstall();

        // Shutdown in reverse order
        if (encryptionManager != null) {
            encryptionManager.dispose();
            encryptionManager = null;
        }
        if (btConnectionManager != null) {
            btConnectionManager.disconnect();
            btConnectionManager = null;
        }
        if (radioControlManager != null) {
            radioControlManager.stop();
            radioControlManager = null;
        }
        if (contactTracker != null) {
            contactTracker.stop();
            contactTracker = null;
        }
        if (cotBridge != null) {
            cotBridge.dispose();
            cotBridge = null;
        }
        if (chatBridge != null) {
            chatBridge.dispose();
            chatBridge = null;
        }
        if (beaconIntervalReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(beaconIntervalReceiver);
            } catch (Exception ignored) {
            }
            beaconIntervalReceiver = null;
        }
        if (mapItemClickListener != null && view != null) {
            try {
                view.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_CLICK, mapItemClickListener);
            } catch (Exception ignored) {
            }
            mapItemClickListener = null;
        }

        Log.i(TAG, "UV-PRO plugin shutdown complete");
    }

    private static final String UPDATE_SERVER_URL = "https://atakmaps.com/plugins/product.infz";
    private static final String UPDATE_TRUSTSTORE_ASSET = "atakmaps-ca.p12";
    private static final String UPDATE_TRUSTSTORE_LOCAL = "uvpro_update_server_ca.p12";
    private static final String UPDATE_TRUSTSTORE_AUTH = "atakatak";

    /**
     * Configure ATAK plugin-management prefs and install the official update-server truststore
     * (same path as Plugins → gear → Update Server SSL/TLS TrustStore). This is the supported
     * way to trust Let's Encrypt for {@code atakmaps.com} on production ATAK builds.
     */
    private void configureUpdateServer(Context context) {
        try {
            Context atakContext = MapView.getMapView().getContext();
            android.content.SharedPreferences prefs =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakContext);

            java.io.File p12 = new java.io.File(context.getFilesDir(), UPDATE_TRUSTSTORE_LOCAL);
            copyAssetToFile(context, UPDATE_TRUSTSTORE_ASSET, p12);

            prefs.edit()
                    .putString("atakUpdateServerUrl", UPDATE_SERVER_URL)
                    .putBoolean("appMgmtEnableUpdateServer", true)
                    .putBoolean("repoStartupSync", true)
                    .putBoolean("atakPluginScanningOnStartup", true)
                    .putString("updateServerCaLocation", p12.getAbsolutePath())
                    .apply();

            // Store the P12 bytes in ATAK's cert DB (importCertificate reads raw bytes only,
            // format validation happens later when ATAK opens the truststore).
            byte[] imported = AtakCertificateDatabase.importCertificate(
                    p12.getAbsolutePath(),
                    null,
                    AtakCertificateDatabaseIFace.TYPE_UPDATE_SERVER_TRUST_STORE_CA,
                    false);
            if (imported != null) {
                Log.i(TAG, "Update server truststore imported: " + p12.getAbsolutePath());
            } else {
                Log.w(TAG, "AtakCertificateDatabase.importCertificate returned null — file may not exist yet");
            }

            // Always store the truststore auth token regardless of import result so that ATAK's
            // plugin-manager UI shows the field pre-populated and the SSL handshake can
            // open the truststore file when it is ready.
            AtakCertificateDatabase.saveCertificatePassword(
                    UPDATE_TRUSTSTORE_AUTH,
                    AtakAuthenticationCredentials.TYPE_updateServerCaPassword,
                    null);
            Log.i(TAG, "Update server CA credential stored");

            triggerUpdateServerSync();
        } catch (Exception e) {
            Log.w(TAG, "configureUpdateServer failed: " + e.getMessage(), e);
        }
    }

    private static void copyAssetToFile(Context context, String assetName, java.io.File dest)
            throws java.io.IOException {
        try (java.io.InputStream in = context.getAssets().open(assetName);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private java.lang.reflect.Method findSingletonGetter(Class<?> cls) {
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!cls.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    private java.lang.reflect.Method findProviderManagerGetter(Class<?> compClass) {
        for (java.lang.reflect.Method m : compClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            for (java.lang.reflect.Method pm : rt.getMethods()) {
                if (pm.getReturnType() != void.class) continue;
                Class<?>[] p = pm.getParameterTypes();
                if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private java.lang.reflect.Method findSyncMethod(Class<?> mgrClass) {
        for (java.lang.reflect.Method m : mgrClass.getMethods()) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * Trigger a silent sync of ATAK's plugin update server after truststore/prefs are applied.
     */
    private void triggerUpdateServerSync() {
        triggerUpdateServerSync(0);
    }

    private void triggerUpdateServerSync(final int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Class<?> cls = Class.forName("com.atakmap.android.update.ApkUpdateComponent");
                java.lang.reflect.Method singletonGetter = findSingletonGetter(cls);
                if (singletonGetter == null) {
                    Log.w(TAG, "triggerUpdateServerSync: singleton getter not found");
                    return;
                }
                Object comp = singletonGetter.invoke(null);
                if (comp == null) {
                    if (attempt < 8) {
                        Log.d(TAG, "triggerUpdateServerSync: component not ready, retry " + (attempt + 1));
                        triggerUpdateServerSync(attempt + 1);
                    } else {
                        Log.w(TAG, "triggerUpdateServerSync: component still null after retries");
                    }
                    return;
                }
                java.lang.reflect.Method pmGetter = findProviderManagerGetter(cls);
                if (pmGetter == null) {
                    Log.w(TAG, "triggerUpdateServerSync: providerManager getter not found");
                    return;
                }
                Object mgr = pmGetter.invoke(comp);
                if (mgr == null) {
                    Log.w(TAG, "triggerUpdateServerSync: providerManager null");
                    return;
                }
                java.lang.reflect.Method sync = findSyncMethod(mgr.getClass());
                if (sync == null) {
                    Log.w(TAG, "triggerUpdateServerSync: sync(boolean,boolean) not found");
                    return;
                }
                sync.invoke(mgr, true, false); // silent=true, checkIncompat=false
                Log.i(TAG, "triggerUpdateServerSync: sync triggered");
            } catch (Exception e) {
                Log.w(TAG, "triggerUpdateServerSync failed: " + e.getMessage());
            }
        }, 2000);
    }

    /** Auto-connect to the last used radio on startup if one is saved. */
    private void autoConnectLastRadio(Context context) {
        try {
            String tgt = com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry
                    .getConnectTargetAddress(context);
            if (tgt == null || tgt.isEmpty()) {
                Log.d(TAG, "Auto-connect: no saved radio address");
                return;
            }
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.d(TAG, "Auto-connect: Bluetooth not available");
                return;
            }
            android.bluetooth.BluetoothDevice device = adapter.getRemoteDevice(tgt);
            Log.i(TAG, "Auto-connecting to last radio: " + tgt);
            btConnectionManager.connect(device);
        } catch (Exception e) {
            Log.w(TAG, "Auto-connect failed: " + e.getMessage());
        }
    }

    /** Start periodic GPS beacon broadcasts. */
    private void startBeaconTimer() {
        if (beaconHandler != null && beaconRunnable != null) {
            beaconHandler.removeCallbacks(beaconRunnable);
        }
        smartBeacon.reset();

        beaconHandler = new Handler(Looper.getMainLooper());
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                sendBeaconIfConnected();
                // Smart beacon: check every 10s. Fixed: check at interval.
                long nextCheckMs;
                if (SmartBeacon.isEnabled(pluginContext)) {
                    nextCheckMs = 10_000L; // poll every 10s; algorithm decides when to actually send
                } else {
                    int intervalSec = SettingsFragment.getBeaconIntervalSec(pluginContext);
                    if (intervalSec < 1) intervalSec = 1;
                    nextCheckMs = intervalSec * 1000L;
                }
                beaconHandler.postDelayed(this, nextCheckMs);
            }
        };
        beaconHandler.postDelayed(beaconRunnable, 30_000L);
    }

    private void sendBeaconIfConnected() {
        if (btConnectionManager == null || !btConnectionManager.isConnected()) return;
        if (cotBridge == null || mapView == null) return;

        try {
            com.atakmap.android.maps.PointMapItem self = mapView.getSelfMarker();
            if (self == null) return;

            com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();

            // Speed (m/s) and course (degrees) — use getMetaString for broad SDK compat
            double speedMs = 0.0, course = 0.0;
            try { speedMs = Double.parseDouble(self.getMetaString("Speed",  "0")); } catch (Exception ignored) {}
            try { course  = Double.parseDouble(self.getMetaString("course", "0")); } catch (Exception ignored) {}
            double speedMph = speedMs * 2.23694;

            if (SmartBeacon.isEnabled(pluginContext)) {
                if (!smartBeacon.shouldBeacon(pluginContext, speedMph, course)) return;
                smartBeacon.recordBeacon(course);
                Log.d(TAG, "Smart beacon fired (speed=" + String.format("%.1f", speedMph)
                        + "mph, course=" + String.format("%.0f", course) + "°)");
            }

            cotBridge.sendPositionOverRadio(
                    gp.getLatitude(), gp.getLongitude(),
                    gp.getAltitude(), (float) speedMs, (float) course, -1);
            Log.d(TAG, "Periodic beacon sent");
        } catch (Exception e) {
            Log.e(TAG, "Error sending periodic beacon", e);
        }
    }

    /**
     * Get the Bluetooth connection manager (for UI access).
     */
    public BtConnectionManager getBtConnectionManager() {
        return btConnectionManager;
    }
}
