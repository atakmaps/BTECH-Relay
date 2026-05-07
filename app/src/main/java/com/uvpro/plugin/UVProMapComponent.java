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
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketRouter;
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


        // Wire PacketRouter RX count to dropdown UI
        packetRouter.setPacketCountListener(dropDownReceiver);

        // Register the drop-down with ATAK
        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(UVProDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(dropDownReceiver, filter);

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

        Log.i(TAG, "UV-PRO plugin shutdown complete");
    }

    /**
     * Silently configure ATAK's built-in plugin update server on first install.
     * Sets the update server URL, enables auto-sync and update server checks.
     * Runs every launch but only writes prefs when the URL isn't already set.
     */
    private void configureUpdateServer(Context context) {
        try {
            // Must use ATAK's own context — plugin context writes to the wrong prefs file
            Context atakContext = com.atakmap.android.maps.MapView.getMapView().getContext();
            android.content.SharedPreferences prefs =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakContext);
            final String UPDATE_SERVER_URL = "https://atakmaps.com/plugins/product.infz";
            final String PREF_URL         = "atakUpdateServerUrl";
            final String PREF_ENABLED     = "appMgmtEnableUpdateServer";
            final String PREF_AUTO_SYNC   = "app_mgmt_auto_sync";

            String existing = prefs.getString(PREF_URL, "");
            if (!existing.contains("atakmaps.com")) {
                prefs.edit()
                        .putString(PREF_URL,        UPDATE_SERVER_URL)
                        .putBoolean(PREF_ENABLED,   true)
                        .putBoolean(PREF_AUTO_SYNC, true)
                        .apply();
                Log.i(TAG, "Plugin update server configured: " + UPDATE_SERVER_URL);
            }

            // Register the Let's Encrypt CA so ATAK's custom SSL manager trusts atakmaps.com.
            // ATAK's CertificateManagerBase requires a non-empty password entry for
            // UPDATE_SERVER_TRUST_STORE_CA — the settings UI never stores one, so we do it here.
            registerUpdateServerCA(context);

        } catch (Exception e) {
            Log.w(TAG, "configureUpdateServer failed: " + e.getMessage());
        }
    }

    private void registerUpdateServerCA(Context context) {
        try {
            // Parse ISRG Root X1 directly as X509Certificate — no PKCS12 involved.
            // Android's KeyStore.getInstance("PKCS12") does not enumerate trusted-cert-only
            // entries, so loadCertificate() always returns 0. Bypassing that entirely by
            // calling CertificateManager.addCertificate(X509Certificate) which injects
            // the cert into the in-memory certificates list used by getAcceptedIssuers().
            java.io.InputStream is = context.getAssets().open("isrg-root-x1.pem");
            java.security.cert.X509Certificate caCert = (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(is);
            is.close();
            Log.i(TAG, "registerUpdateServerCA: loaded CA cert: " + caCert.getSubjectDN());

            injectCACert(caCert, 0);
        } catch (Exception e) {
            Log.w(TAG, "registerUpdateServerCA failed: " + e.getMessage(), e);
        }
    }

    private void injectCACert(final java.security.cert.X509Certificate cert, final int attempt) {
        try {
            Class<?> certMgrClass = Class.forName("com.atakmap.net.CertificateManager");

            // Find getInstance() — ProGuard renames it, so match by signature:
            // static, no params, returns same class.
            java.lang.reflect.Method getInst = null;
            for (java.lang.reflect.Method m : certMgrClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0
                        && certMgrClass.isAssignableFrom(m.getReturnType())) {
                    getInst = m;
                    getInst.setAccessible(true);
                    break;
                }
            }
            if (getInst == null) {
                Log.w(TAG, "injectCACert: getInstance() not found");
                return;
            }
            Object certMgr = getInst.invoke(null);

            // Find _impl field — the gov.tak.platform.engine.net.CertificateManager instance.
            java.lang.reflect.Field implField = null;
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getType().getName().contains("CertificateManager")) {
                    implField = f;
                    implField.setAccessible(true);
                    break;
                }
            }
            Object impl = (implField != null) ? implField.get(certMgr) : null;

            if (impl == null) {
                // CertificateManager.initialize() hasn't been called yet — retry shortly.
                if (attempt < 15) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> injectCACert(cert, attempt + 1), 1000);
                    Log.d(TAG, "injectCACert: _impl null, retry " + (attempt + 1));
                } else {
                    Log.w(TAG, "injectCACert: _impl still null after " + attempt + " retries");
                }
                return;
            }

            // Call addCertificate(X509Certificate) on _impl directly.
            // This adds to _impl.certificates list and calls _impl.refresh() which
            // invalidates the localTrustManager so it is rebuilt with our CA included.
            java.lang.reflect.Method addCert =
                    impl.getClass().getMethod("addCertificate",
                            java.security.cert.X509Certificate.class);
            addCert.invoke(impl, cert);
            Log.i(TAG, "injectCACert: cert injected into _impl.certificates (attempt " + attempt + ")");

            // Also clear the outer socketFactories cache — these are built once and cached;
            // any factory created before our cert was injected has stale trust context.
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object map = f.get(null);
                    if (map instanceof java.util.Map) {
                        ((java.util.Map<?, ?>) map).clear();
                        Log.i(TAG, "injectCACert: socketFactories cache cleared");
                    }
                }
            }

            Log.i(TAG, "Update server CA registered successfully");

        } catch (Exception e) {
            Log.w(TAG, "injectCACert failed (attempt " + attempt + "): " + e.getMessage(), e);
        }
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
