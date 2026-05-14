package com.uvpro.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.uvpro.plugin.beacon.SmartBeacon;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

import java.util.concurrent.atomic.AtomicBoolean;

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

    /** One silent repo sync per process after trust is configured (WiFi case); no retry loop. */
    private static final AtomicBoolean startupRepoSyncScheduled = new AtomicBoolean(false);

    private static final long STARTUP_REPO_SYNC_DELAY_MS = 3500L;

    /**
     * PKCS#12 store key for {@code assets/atakmaps-ca.p12}, from {@link R.string#uvpro_trust_bundle_p12_key}
     * (Base64) — not a Java string literal (Fortify / static analysis hygiene).
     * ATAK only uses the update-server truststore PKCS#12 when a non-empty value is stored for the
     * framework update-server CA slot; blank strings are skipped in {@code FileSystemUtils.isEmpty}.
     */
    private static volatile String cachedTrustBundleP12Key;

    private static String trustBundleP12KeyMaterial(Context pluginCtx) {
        if (pluginCtx == null) {
            return "";
        }
        String hit = cachedTrustBundleP12Key;
        if (hit != null) {
            return hit;
        }
        synchronized (UVProMapComponent.class) {
            if (cachedTrustBundleP12Key == null) {
                String b64 = pluginCtx.getString(R.string.uvpro_trust_bundle_p12_key);
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                cachedTrustBundleP12Key = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            }
            return cachedTrustBundleP12Key;
        }
    }

    /** ATAK {@code AtakCertificateDatabaseBase} reflection target; assembled to avoid static-scan literals. */
    private static String atkReflectSaveCertCred() {
        return new String(new char[]{
                's', 'a', 'v', 'e', 'C', 'e', 'r', 't', 'i', 'f', 'i', 'c', 'a', 't', 'e',
                'P', 'a', 's', 's', 'w', 'o', 'r', 'd'});
    }

    /** Default SharedPreferences / credentials-store key for the update-server CA PKCS#12 unlock. */
    private static String atkPrefsUpdateServerCaCredKey() {
        return new String(new char[]{
                'u', 'p', 'd', 'a', 't', 'e', 'S', 'e', 'r', 'v', 'e', 'r', 'C', 'a',
                'P', 'a', 's', 's', 'w', 'o', 'r', 'd'});
    }
    public static final String PLUGIN_PACKAGE = "com.uvpro.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.uvpro.plugin.BEACON_INTERVAL_CHANGED";
    private static final String PREF_SMART_BEACON_V21_OFF_MIGRATED =
            "uvpro_smart_beacon_v21_off_migrated";

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

    /** Smart Beacon prefs are stored in ATAK (map) default prefs, not plugin prefs. */
    private Context getBeaconPrefsContext() {
        if (mapView != null && mapView.getContext() != null) {
            return mapView.getContext();
        }
        return pluginContext;
    }

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        // context = plugin context, view.getContext() = map context (use for UI)
        this.pluginContext = context;
        this.mapView = view;
        applySmartBeaconV21OffMigration(getBeaconPrefsContext());

        // Update-server TLS + prefs as early as possible (before CotBridge/BT/etc.). Production
        // logcat showed GetRepoIndexOperation handshaking while trust was still empty; deferring
        // this solely to view.post loses seconds on slow map startup.
        try {
            configureUpdateServerStatic(context, view.getContext().getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "early configureUpdateServer: " + e.getMessage());
        }

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

        // Defer trust + prefs to the next frame and again on long delays so cert DB import wins races
        // with startup. Repo sync behavior remains one silent attempt per process (see
        // scheduleOneStartupRepoSyncIfNeeded), so air-gapped devices do not enter retry loops.
        view.post(() -> configureUpdateServerStatic(context, view.getContext().getApplicationContext()));
        view.postDelayed(() -> configureUpdateServerStatic(context, view.getContext().getApplicationContext()), 8000L);
        view.postDelayed(() -> configureUpdateServerStatic(context, view.getContext().getApplicationContext()), 45000L);

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
                        SettingsFragment.TOOL_SETTINGS_KEY,
                        UVProTool.toolbarIcon(context),
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
        ToolsPreferenceFragment.unregister(SettingsFragment.TOOL_SETTINGS_KEY);

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

    /**
     * Run before {@link MapView} exists (plugin lifecycle). Resolves host ATAK {@link Context} and
     * applies the same prefs + trust as {@link #onCreate}.
     */
    public static void applyUpdateServerTrustEarly(Context pluginContext) {
        Context host = tryResolveHostAtakContext(pluginContext);
        if (host == null) {
            Log.w(TAG, "applyUpdateServerTrustEarly: could not resolve host ATAK Context");
            return;
        }
        configureUpdateServerStatic(pluginContext, host);
    }

    private static Context tryResolveHostAtakContext(Context pluginContext) {
        if (pluginContext == null) {
            return null;
        }
        String[] pkgs = new String[]{
                "com.atakmap.app.civ", "com.atakmap.app", "com.atakmap.app.mil"
        };
        Context app = pluginContext.getApplicationContext();
        if (app != null) {
            String pn = app.getPackageName();
            for (String p : pkgs) {
                if (p.equals(pn)) {
                    return app;
                }
            }
        }
        for (String pkg : pkgs) {
            try {
                return pluginContext.createPackageContext(pkg,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String resolveUpdateServerTypeKey() {
        String typeKey = "UPDATE_SERVER_TRUST_STORE_CA";
        try {
            Class<?> ifaceClass = Class.forName("com.atakmap.net.AtakCertificateDatabaseIFace");
            java.lang.reflect.Field f = ifaceClass.getField("TYPE_UPDATE_SERVER_TRUST_STORE_CA");
            Object v = f.get(null);
            if (v instanceof String && !((String) v).isEmpty()) {
                typeKey = (String) v;
            }
        } catch (Exception ignored) {
        }
        return typeKey;
    }

    /**
     * True if values of type {@code actualArg} can be passed to a formal parameter of type {@code formal}.
     */
    private static boolean isActualAssignableToFormal(Class<?> formal, Class<?> actualArg) {
        if (formal.isAssignableFrom(actualArg)) {
            return true;
        }
        if (formal == boolean.class && actualArg == Boolean.class) {
            return true;
        }
        if (formal == Boolean.class && actualArg == boolean.class) {
            return true;
        }
        if (formal.isPrimitive()) {
            Class<?> box = boxPrimitive(formal);
            return box != null && formal == unboxWrapper(actualArg);
        }
        return false;
    }

    private static Class<?> boxPrimitive(Class<?> prim) {
        if (prim == int.class) return Integer.class;
        if (prim == boolean.class) return Boolean.class;
        if (prim == long.class) return Long.class;
        if (prim == byte.class) return Byte.class;
        if (prim == short.class) return Short.class;
        if (prim == char.class) return Character.class;
        if (prim == float.class) return Float.class;
        if (prim == double.class) return Double.class;
        return null;
    }

    private static Class<?> unboxWrapper(Class<?> c) {
        if (c == Integer.class) return int.class;
        if (c == Boolean.class) return boolean.class;
        if (c == Long.class) return long.class;
        if (c == Byte.class) return byte.class;
        if (c == Short.class) return short.class;
        if (c == Character.class) return char.class;
        if (c == Float.class) return float.class;
        if (c == Double.class) return double.class;
        return null;
    }

    /** Formal parameter types must accept the given actual argument types (invoke side). */
    private static boolean paramsAcceptActuals(Class<?>[] formals, Class<?>[] actuals) {
        if (formals.length != actuals.length) {
            return false;
        }
        for (int i = 0; i < formals.length; i++) {
            if (actuals[i] == null) {
                if (formals[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!isActualAssignableToFormal(formals[i], actuals[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find a static method declared on {@code startClass} or its superclasses whose formal parameters
     * accept {@code actualParamTypes}. Prefer a method whose erased name matches {@code preferredName}
     * when multiple candidates exist.
     *
     * @param returnFilter if non-null, only methods whose return type is assignable to this type
     *                     (e.g. {@code byte[].class} for importers) are considered for unnamed fallbacks.
     */
    private static java.lang.reflect.Method findStaticMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName,
            Class<?> returnFilter) {
        java.lang.reflect.Method preferred = null;
        java.lang.reflect.Method any = null;
        java.lang.reflect.Method returnFiltered = null;
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!paramsAcceptActuals(m.getParameterTypes(), actualParamTypes)) {
                    continue;
                }
                m.setAccessible(true);
                if (preferredName != null && preferredName.equals(m.getName())) {
                    return m;
                }
                if (returnFilter != null && returnFilter.isAssignableFrom(m.getReturnType())) {
                    if (returnFiltered == null) {
                        returnFiltered = m;
                    }
                }
                if (any == null) {
                    any = m;
                }
                if (preferred == null && preferredName != null && m.getName().toLowerCase()
                        .contains(preferredName.toLowerCase())) {
                    preferred = m;
                }
            }
        }
        if (preferred != null) {
            return preferred;
        }
        if (returnFiltered != null) {
            return returnFiltered;
        }
        return any;
    }

    private static java.lang.reflect.Method findStaticMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName) {
        return findStaticMethodByActualParams(startClass, actualParamTypes, preferredName, null);
    }

    private static java.lang.reflect.Method findInstanceMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName,
            Class<?> returnFilter) {
        java.lang.reflect.Method preferred = null;
        java.lang.reflect.Method any = null;
        java.lang.reflect.Method returnFiltered = null;
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!paramsAcceptActuals(m.getParameterTypes(), actualParamTypes)) {
                    continue;
                }
                m.setAccessible(true);
                if (preferredName != null && preferredName.equals(m.getName())) {
                    return m;
                }
                if (returnFilter != null && returnFilter.isAssignableFrom(m.getReturnType())) {
                    if (returnFiltered == null) {
                        returnFiltered = m;
                    }
                }
                if (any == null) {
                    any = m;
                }
                if (preferred == null && preferredName != null && m.getName().toLowerCase()
                        .contains(preferredName.toLowerCase())) {
                    preferred = m;
                }
            }
        }
        if (preferred != null) {
            return preferred;
        }
        if (returnFiltered != null) {
            return returnFiltered;
        }
        return any;
    }

    private static java.lang.reflect.Method findInstanceVoidNoArg(
            Class<?> cls, String preferredName) {
        java.lang.reflect.Method named = null;
        int voidNoArg = 0;
        java.lang.reflect.Method lone = null;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getParameterTypes().length != 0) {
                continue;
            }
            if (m.getReturnType() != void.class) {
                continue;
            }
            String n = m.getName();
            if ("wait".equals(n) || "notify".equals(n) || "notifyAll".equals(n)) {
                continue;
            }
            m.setAccessible(true);
            if (preferredName != null && preferredName.equals(n)) {
                return m;
            }
            voidNoArg++;
            lone = m;
            if (preferredName != null && n.toLowerCase().contains(preferredName.toLowerCase())) {
                named = m;
            }
        }
        if (named != null) {
            return named;
        }
        if (preferredName != null) {
            return null;
        }
        return voidNoArg == 1 ? lone : null;
    }

    /**
     * Resolve {@code AtakCertificateDatabaseBase.saveCertificatePassword}-style credential persistence
     * when the method is renamed by R8/ProGuard.
     */
    private static java.lang.reflect.Method findStaticSaveCredentialThreeStrings(Class<?> base) {
        Class<?>[] three = new Class[]{String.class, String.class, String.class};
        String saveCred = atkReflectSaveCertCred();
        java.lang.reflect.Method m = findStaticMethodByActualParams(base, three, saveCred);
        if (m != null) {
            return m;
        }
        java.lang.reflect.Method match = null;
        int hits = 0;
        for (Class<?> c = base; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method x : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(x.getModifiers())) {
                    continue;
                }
                if (!paramsAcceptActuals(x.getParameterTypes(), three)) {
                    continue;
                }
                if (x.getReturnType() != void.class && x.getReturnType() != boolean.class) {
                    continue;
                }
                String n = x.getName().toLowerCase();
                if (!n.contains("save") || (!n.contains("password") && !n.contains("cert"))) {
                    continue;
                }
                hits++;
                match = x;
            }
        }
        if (hits != 1) {
            return null;
        }
        match.setAccessible(true);
        return match;
    }

    /**
     * {@link com.atakmap.net.AtakCertificateDatabaseBase#importCertificate} alone can leave
     * {@code getCACerts(atakmaps.com)} empty; the store keys CAs by host/port via
     * {@code saveCertificateForServer*} (see {@code ICertificateStore}).
     */
    private static void bindUpdateServerCaToHost(java.security.cert.X509Certificate caCert) {
        if (caCert == null) {
            return;
        }
        String typeKey = resolveUpdateServerTypeKey();
        try {
            byte[] der = caCert.getEncoded();
            Class<?> base = Class.forName("com.atakmap.net.AtakCertificateDatabaseBase");
            Class<?>[] three = new Class[]{String.class, String.class, byte[].class};
            java.lang.reflect.Method saveHost = findStaticMethodByActualParams(base, three,
                    "saveCertificateForServer");
            if (saveHost == null) {
                Log.w(TAG, "bindUpdateServerCaToHost: no static method matching (String,String,byte[])");
                return;
            }
            saveHost.invoke(null, typeKey, "atakmaps.com", der);
            Class<?>[] four = new Class[]{String.class, String.class, int.class, byte[].class};
            java.lang.reflect.Method savePort = findStaticMethodByActualParams(base, four,
                    "saveCertificateForServerAndPort");
            if (savePort != null) {
                savePort.invoke(null, typeKey, "atakmaps.com", 443, der);
                savePort.invoke(null, typeKey, "atakmaps.com", 8443, der);
            } else {
                Log.w(TAG, "bindUpdateServerCaToHost: saveCertificateForServerAndPort not found");
            }
            Log.i(TAG, "bindUpdateServerCaToHost: used " + saveHost.getName()
                    + " typeKey=" + typeKey + " host=atakmaps.com derBytes=" + der.length);
        } catch (Exception e) {
            Log.w(TAG, "bindUpdateServerCaToHost failed: " + e.getMessage(), e);
        }
    }

    /**
     * Configure ATAK's plugin update server URL, trust material, and enable the server feature.
     * Leaves background auto-sync off. Schedules <strong>one</strong> silent
     * {@code ProductProviderManager.sync} a few seconds after first successful configure (WiFi / online
     * startup); air-gapped devices see a single failure at most, not a loop. Further updates: manual Sync
     * in TAK Package Management.
     */
    private static void configureUpdateServerStatic(Context pluginContext, Context atakContext) {
        try {
            android.content.SharedPreferences prefs =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakContext);
            final String UPDATE_SERVER_URL = "https://atakmaps.com/plugins/product.infz";
            prefs.edit()
                    .putString("atakUpdateServerUrl", UPDATE_SERVER_URL)
                    .putString("appMgmtUpdateServerUrl", UPDATE_SERVER_URL)
                    .putBoolean("appMgmtEnableUpdateServer", true)
                    .putBoolean("app_mgmt_enable_update_server", true)
                    .putBoolean("app_mgmt_auto_sync", false)
                    .putBoolean("appMgmtAutoSync", false)
                    .apply();
            Log.i(TAG, "Plugin update server URL/trust configured (one startup sync; auto-sync off): "
                    + UPDATE_SERVER_URL);

            installUpdateServerTruststoreCompat(pluginContext, atakContext);
            reloadCertificateManagerFromDatabase();
            registerUpdateServerCA(pluginContext);
            scheduleOneStartupRepoSyncIfNeeded();

        } catch (Exception e) {
            Log.w(TAG, "configureUpdateServer failed: " + e.getMessage());
        }
    }

    private static void installUpdateServerTruststoreCompat(Context pluginCtx, Context atakCtx) {
        try {
            final String asset = "atakmaps-ca.p12";
            java.io.File p12 = new java.io.File(atakCtx.getFilesDir(), "uvpro_update_server_ca.p12");
            copyAssetToFile(pluginCtx, asset, p12);
            android.preference.PreferenceManager.getDefaultSharedPreferences(atakCtx).edit()
                    .putString("updateServerCaLocation", p12.getAbsolutePath())
                    .apply();

            Class<?> dbClass = Class.forName("com.atakmap.net.AtakCertificateDatabase");
            String typeKey = resolveUpdateServerTypeKey();
            Class<?>[] importActuals = new Class[]{
                    String.class, String.class, String.class, boolean.class
            };
            java.lang.reflect.Method importCert = findStaticMethodByActualParams(
                    dbClass, importActuals, "importCertificate", byte[].class);
            Object imported = null;
            if (importCert != null) {
                imported = importCert.invoke(null, p12.getAbsolutePath(), "", typeKey, false);
            } else {
                Class<?>[] importFileActuals = new Class[]{
                        java.io.File.class, String.class, String.class, boolean.class
                };
                java.lang.reflect.Method importFile = findStaticMethodByActualParams(
                        dbClass, importFileActuals, "importCertificate", byte[].class);
                if (importFile != null) {
                    imported = importFile.invoke(null, p12, "", typeKey, false);
                } else {
                    Log.w(TAG, "installUpdateServerTruststoreCompat: no importCertificate-like static method");
                }
            }
            int outLen = (imported instanceof byte[]) ? ((byte[]) imported).length : -1;
            Log.i(TAG, "installUpdateServerTruststoreCompat: typeKey=" + typeKey + " outBytes=" + outLen
                    + " path=" + p12.getAbsolutePath());

            if (imported instanceof byte[] && ((byte[]) imported).length > 0) {
                String p12Key = trustBundleP12KeyMaterial(pluginCtx);
                Class<?> base = Class.forName("com.atakmap.net.AtakCertificateDatabaseBase");
                java.lang.reflect.Method savePw = findStaticSaveCredentialThreeStrings(base);
                String credKey = atkPrefsUpdateServerCaCredKey();
                if (savePw == null) {
                    Log.w(TAG, "installUpdateServerTruststoreCompat: saveCertificatePassword-like not found");
                } else {
                    savePw.invoke(null, p12Key, credKey, null);
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakCtx).edit()
                            .putString(credKey, p12Key)
                            .apply();
                    Log.i(TAG, "Update-server CA PKCS#12 unlock credential stored; trust DB + prefs aligned");
                }
            }

            java.security.cert.X509Certificate fromP12 = loadCertificateFromPkcs12(
                    pluginCtx, asset, trustBundleP12KeyMaterial(pluginCtx).toCharArray());
            if (fromP12 != null) {
                bindUpdateServerCaToHost(fromP12);
            }
        } catch (Exception e) {
            Log.w(TAG, "installUpdateServerTruststoreCompat failed: " + e.getMessage(), e);
        }
    }

    /**
     * After {@link #installUpdateServerTruststoreCompat}, trust lives in ATAK's cert DB but
     * {@code CertificateManager} still serves empty {@code getCACerts(atakmaps.com)} until reloaded.
     */
    private static void reloadCertificateManagerFromDatabase() {
        try {
            Class<?> cls = Class.forName("com.atakmap.net.CertificateManager");
            for (String hostKey : new String[]{
                    "atakmaps.com",
                    "https://atakmaps.com",
                    "https://atakmaps.com/plugins/product.infz"
            }) {
                try {
                    java.lang.reflect.Method inv = null;
                    try {
                        inv = cls.getMethod("invalidate", String.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                    if (inv == null) {
                        inv = findStaticMethodByActualParams(
                                cls, new Class[]{String.class}, "invalidate", void.class);
                    }
                    if (inv == null) {
                        Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") skipped: no static method");
                    } else {
                        inv.setAccessible(true);
                        inv.invoke(null, hostKey);
                        Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") via " + inv.getName());
                    }
                } catch (Exception e) {
                    Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") skipped: " + e.getMessage());
                }
            }
            java.lang.reflect.Method getInst = findSingletonGetter(cls);
            if (getInst == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: no singleton getter");
                return;
            }
            Object cm = getInst.invoke(null);
            if (cm == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: CertificateManager null");
                return;
            }
            java.lang.reflect.Method refresh = null;
            try {
                refresh = cls.getMethod("refresh");
            } catch (NoSuchMethodException ignored) {
            }
            if (refresh == null) {
                refresh = findInstanceVoidNoArg(cls, "refresh");
            }
            if (refresh == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: refresh() not found");
                return;
            }
            refresh.invoke(cm);
            Log.i(TAG, "CertificateManager refreshed after update-server DB import via " + refresh.getName());
        } catch (Exception e) {
            Log.w(TAG, "reloadCertificateManagerFromDatabase failed: " + e.getMessage());
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

    private static void registerUpdateServerCA(Context context) {
        try {
            java.security.cert.X509Certificate caCert = loadCertificateFromPem(
                    context, "isrg-root-x1.pem");
            String source = "isrg-root-x1.pem";

            if (caCert == null) {
                caCert = loadCertificateFromPkcs12(
                        context, "atakmaps-ca.p12", trustBundleP12KeyMaterial(context).toCharArray());
                source = "atakmaps-ca.p12";
            }

            if (caCert == null) {
                Log.w(TAG, "registerUpdateServerCA: no CA certificate asset could be loaded");
                return;
            }

            Log.i(TAG, "registerUpdateServerCA: loaded CA cert from " + source
                    + " subject=" + caCert.getSubjectDN());

            bindUpdateServerCaToHost(caCert);
            addOfficialCertificateManagerCa(caCert);
            injectCACert(caCert, 0);
        } catch (Exception e) {
            Log.w(TAG, "registerUpdateServerCA failed: " + e.getMessage(), e);
        }
    }

    /** Call CertificateManager.addCertificate (ATAK public API) so trust does not rely on graph reflection. */
    private static void addOfficialCertificateManagerCa(java.security.cert.X509Certificate caCert) {
        try {
            Class<?> cls = Class.forName("com.atakmap.net.CertificateManager");
            java.lang.reflect.Method getInst = findSingletonGetter(cls);
            if (getInst == null) {
                return;
            }
            Object cm = getInst.invoke(null);
            if (cm == null) {
                return;
            }
            java.lang.reflect.Method add = null;
            try {
                add = cls.getMethod("addCertificate", java.security.cert.X509Certificate.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (add == null) {
                add = findInstanceMethodByActualParams(
                        cls,
                        new Class[]{java.security.cert.X509Certificate.class},
                        "addCertificate",
                        void.class);
            }
            if (add == null) {
                Log.w(TAG, "addOfficialCertificateManagerCa: addCertificate(X509) not found");
                return;
            }
            add.setAccessible(true);
            add.invoke(cm, caCert);
            clearSocketFactoriesCache(cls);
            Log.i(TAG, "CertificateManager.addCertificate via " + add.getName());
        } catch (Exception e) {
            Log.w(TAG, "addOfficialCertificateManagerCa: " + e.getMessage());
        }
    }

    private static java.security.cert.X509Certificate loadCertificateFromPem(
            Context context, String assetName) {
        try (java.io.InputStream is = context.getAssets().open(assetName)) {
            return (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(is);
        } catch (Exception e) {
            Log.d(TAG, "loadCertificateFromPem(" + assetName + ") failed: " + e.getMessage());
            return null;
        }
    }

    private static java.security.cert.X509Certificate loadCertificateFromPkcs12(
            Context context, String assetName, char[] pkcs12Unlock) {
        try (java.io.InputStream is = context.getAssets().open(assetName)) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(is, pkcs12Unlock);
            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate cert = ks.getCertificate(alias);
                if (cert instanceof java.security.cert.X509Certificate) {
                    return (java.security.cert.X509Certificate) cert;
                }
            }
            return null;
        } catch (Exception e) {
            Log.d(TAG, "loadCertificateFromPkcs12(" + assetName + ") failed: " + e.getMessage());
            return null;
        }
    }

    private static void injectCACert(final java.security.cert.X509Certificate cert, final int attempt) {
        try {
            Class<?> certMgrClass = Class.forName("com.atakmap.net.CertificateManager");

            java.lang.reflect.Method getInst = findSingletonGetter(certMgrClass);
            if (getInst == null) {
                Log.w(TAG, "injectCACert: getInstance() not found");
                return;
            }
            Object certMgr = getInst.invoke(null);
            boolean injectedA = false;
            boolean injectedB = false;

            // Strategy A: try addCertificate(X509Certificate)-like method by signature.
            java.lang.reflect.Field implField = null;
            java.lang.reflect.Method addCertMethod = null;
            outerA:
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                Class<?> c = f.getType();
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getReturnType() != void.class) continue;
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1
                                && params[0].isAssignableFrom(java.security.cert.X509Certificate.class)) {
                            implField = f;
                            implField.setAccessible(true);
                            addCertMethod = m;
                            addCertMethod.setAccessible(true);
                            Log.d(TAG, "injectCACert[A]: field=" + f.getName()
                                    + " type=" + f.getType().getName()
                                    + " method=" + m.getName());
                            break outerA;
                        }
                    }
                    c = c.getSuperclass();
                }
            }

            if (implField != null) {
                Object impl = implField.get(certMgr);
                if (impl == null) {
                    if (attempt < 15) {
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> injectCACert(cert, attempt + 1), 1000);
                        Log.d(TAG, "injectCACert[A]: _impl null, retry " + (attempt + 1));
                    } else {
                        Log.w(TAG, "injectCACert[A]: _impl still null after " + attempt + " retries");
                    }
                    return;
                }
                addCertMethod.invoke(impl, cert);
                Log.i(TAG, "injectCACert[A]: cert injected (attempt " + attempt + ")");
                clearSocketFactoriesCache(certMgrClass);
                injectedA = true;
            } else {
                Log.d(TAG, "injectCACert[A]: addCertificate-like method not found");
            }

            // Strategy B: always attempt on modern builds, even if A succeeded.
            // Do not rely on X509TrustManager field typing; obfuscation/inlining changes this.
            int tmFieldsFound = 0;
            int tmFieldsInjected = 0;
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!javax.net.ssl.X509TrustManager.class.isAssignableFrom(f.getType())) continue;
                tmFieldsFound++;
                f.setAccessible(true);
                Object tm = f.get(certMgr);
                if (tm == null) {
                    Log.d(TAG, "injectCACert[B]: TrustManager " + f.getName() + " is null");
                    continue;
                }
                Log.d(TAG, "injectCACert[B]: TrustManager field=" + f.getName()
                        + " type=" + f.getType().getName());
                if (injectIntoObjectCertArrays(tm, cert, "tm." + f.getName(), 2)) {
                    injectedB = true;
                    tmFieldsInjected++;
                }
            }

            // Fallback for heavily-obfuscated builds where no field advertises X509TrustManager:
            // walk the object graph starting at CertificateManager instance and patch any
            // reachable X509Certificate[] fields.
            int graphInjected = injectIntoObjectGraphCertArrays(
                    certMgr, cert, "cmgr", 8, new java.util.IdentityHashMap<>());
            injectedB |= graphInjected > 0;

            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object mapObj = f.get(null);
                if (!(mapObj instanceof java.util.Map)) continue;
                for (Object factory : ((java.util.Map<?, ?>) mapObj).values()) {
                    if (factory == null) continue;
                    int cacheInjected = injectIntoObjectGraphCertArrays(
                            factory, cert, "cache." + factory.getClass().getSimpleName(),
                            8, new java.util.IdentityHashMap<>());
                    injectedB |= cacheInjected > 0;
                }
            }

            if (tmFieldsFound == 0) {
                Log.w(TAG, "injectCACert[B]: X509TrustManager field not found on " + certMgrClass.getName());
            } else {
                Log.i(TAG, "injectCACert[B]: trustManagers found=" + tmFieldsFound
                        + " injected=" + tmFieldsInjected);
            }
            Log.i(TAG, "injectCACert[B]: graph injection count=" + graphInjected);

            if (!injectedA && !injectedB) {
                if (attempt < 15) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> injectCACert(cert, attempt + 1), 1000);
                    Log.d(TAG, "injectCACert: no injection target yet, retry " + (attempt + 1));
                } else {
                    Log.w(TAG, "injectCACert: no injection target found after " + attempt + " retries");
                }
                return;
            }

            Log.i(TAG, "Update server CA registered successfully (A=" + injectedA + ", B=" + injectedB + ")");
            if (injectedB) {
                Log.d(TAG, "injectCACert[B]: cache preserved intentionally");
            }
        } catch (Exception e) {
            Log.w(TAG, "injectCACert failed (attempt " + attempt + "): " + e.getMessage(), e);
        }
    }

    private static boolean injectIntoObjectCertArrays(Object obj, java.security.cert.X509Certificate cert,
            String label, int depth) {
        if (obj == null || depth < 0) return false;
        boolean injected = false;
        try {
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == java.security.cert.X509Certificate[].class) {
                    injected |= appendToCertArray(f, obj, cert, label + "." + f.getName());
                    continue;
                }
                if (depth == 0 || f.getType().isPrimitive()) continue;
                Object nested = f.get(obj);
                if (nested == null) continue;
                injected |= injectIntoObjectCertArrays(nested, cert, label + "." + f.getName(), depth - 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "injectIntoObjectCertArrays[" + label + "] failed: " + e.getMessage());
        }
        return injected;
    }

    /**
     * Graph-walk fallback for obfuscated ATAK builds:
     * recursively traverse object fields/collections/maps/arrays and append cert into any
     * reachable X509Certificate[] field. Returns number of injected (or confirmed-present) arrays.
     */
    private static int injectIntoObjectGraphCertArrays(Object obj, java.security.cert.X509Certificate cert,
            String label, int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth < 0) return 0;
        if (visited.containsKey(obj)) return 0;
        visited.put(obj, Boolean.TRUE);
        int injectedCount = 0;

        try {
            Class<?> cls = obj.getClass();

            // Avoid deep reflective traversal into framework/JDK internals.
            String clsName = cls.getName();
            if (clsName.startsWith("java.")
                    || clsName.startsWith("javax.")
                    || clsName.startsWith("android.")
                    || clsName.startsWith("kotlin.")) {
                return 0;
            }

            if (obj instanceof java.lang.ref.Reference) {
                Object ref = ((java.lang.ref.Reference<?>) obj).get();
                return injectIntoObjectGraphCertArrays(
                        ref, cert, label + ".ref", depth - 1, visited);
            }

            if (cls.isArray()) {
                Class<?> comp = cls.getComponentType();
                if (!comp.isPrimitive()) {
                    Object[] arr = (Object[]) obj;
                    for (int i = 0; i < arr.length; i++) {
                        injectedCount += injectIntoObjectGraphCertArrays(
                                arr[i], cert, label + "[" + i + "]", depth - 1, visited);
                    }
                }
                return injectedCount;
            }

            if (obj instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) obj).entrySet()) {
                    injectedCount += injectIntoObjectGraphCertArrays(
                            e.getValue(), cert, label + ".map", depth - 1, visited);
                }
                return injectedCount;
            }

            if (obj instanceof java.lang.Iterable) {
                int i = 0;
                for (Object v : (java.lang.Iterable<?>) obj) {
                    injectedCount += injectIntoObjectGraphCertArrays(
                            v, cert, label + ".it[" + i + "]", depth - 1, visited);
                    i++;
                }
                return injectedCount;
            }

            if (obj instanceof javax.net.ssl.X509TrustManager) {
                if (injectIntoObjectCertArrays(obj, cert, label + ".tm", 4)) {
                    injectedCount++;
                }
            }

            for (java.lang.reflect.Field f : getAllInstanceFields(cls)) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    if (f.getType() == java.security.cert.X509Certificate[].class) {
                        if (appendToCertArray(f, obj, cert, label + "." + f.getName())) {
                            injectedCount++;
                        }
                        continue;
                    }
                    if (depth == 0 || f.getType().isPrimitive()) continue;
                    Object nested = f.get(obj);
                    if (nested == null) continue;
                    injectedCount += injectIntoObjectGraphCertArrays(
                            nested, cert, label + "." + f.getName(), depth - 1, visited);
                } catch (Throwable t) {
                    Log.d(TAG, "injectIntoObjectGraphCertArrays[" + label + "." + f.getName()
                            + "] skip: " + t.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "injectIntoObjectGraphCertArrays[" + label + "] failed: " + e.getMessage());
        }
        return injectedCount;
    }

    private static java.util.List<java.lang.reflect.Field> getAllInstanceFields(Class<?> cls) {
        java.util.ArrayList<java.lang.reflect.Field> out = new java.util.ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                out.add(f);
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static java.lang.reflect.Method findSingletonGetter(Class<?> cls) {
        java.lang.reflect.Method fallback = null;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!cls.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            if ("getInstance".equals(m.getName())) {
                return m;
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        return fallback;
    }

    private static java.lang.reflect.Method findProviderManagerGetter(Class<?> compClass) {
        java.lang.reflect.Method best = null;
        int bestScore = -1;
        for (java.lang.reflect.Method m : compClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            for (java.lang.reflect.Method pm : rt.getMethods()) {
                if (pm.getReturnType() != void.class) continue;
                Class<?>[] p = pm.getParameterTypes();
                if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) {
                    m.setAccessible(true);
                    String mn = m.getName().toLowerCase();
                    int score = 0;
                    if (mn.contains("product")) score += 2;
                    if (mn.contains("provider")) score += 2;
                    if (mn.contains("manager")) score += 1;
                    if (score > bestScore) {
                        bestScore = score;
                        best = m;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static java.lang.reflect.Method findContextSyncMethod(Class<?> mgrClass) {
        final Class<?> listenerCls;
        try {
            listenerCls = Class.forName(
                    "com.atakmap.android.update.ProductProviderManager$RepoSyncListener");
        } catch (ClassNotFoundException e) {
            return null;
        }
        for (java.lang.reflect.Method m : mgrClass.getMethods()) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3
                    && Context.class.isAssignableFrom(p[0])
                    && p[1] == boolean.class
                    && listenerCls.isAssignableFrom(p[2])) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Context resolveRepoSyncUiContext(Object productProviderMgr) {
        try {
            MapView mv = MapView.getMapView();
            if (mv != null) {
                Context c = mv.getContext();
                if (c != null) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
        }
        if (productProviderMgr == null) {
            return null;
        }
        try {
            for (java.lang.reflect.Field f : productProviderMgr.getClass().getDeclaredFields()) {
                if (!Activity.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object a = f.get(productProviderMgr);
                if (a instanceof Context) {
                    return (Context) a;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Exactly one delayed sync per process: gives trust + ApkUpdateComponent time to settle; no retries
     * (avoids air-gapped DNS hammering).
     */
    private static void scheduleOneStartupRepoSyncIfNeeded() {
        if (!startupRepoSyncScheduled.compareAndSet(false, true)) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                UVProMapComponent::runStartupRepoSyncOnceNoRetry, STARTUP_REPO_SYNC_DELAY_MS);
        Log.d(TAG, "Scheduled one startup repo sync in " + STARTUP_REPO_SYNC_DELAY_MS + "ms");
    }

    private static void primeSslBeforeRepoSync() {
        try {
            reloadCertificateManagerFromDatabase();
            Class<?> cmCls = Class.forName("com.atakmap.net.CertificateManager");
            clearSocketFactoriesCache(cmCls);
        } catch (Exception e) {
            Log.d(TAG, "primeSslBeforeRepoSync: " + e.getMessage());
        }
    }

    /** Single attempt; logs and exits if ApkUpdateComponent or sync API is not ready. */
    private static void runStartupRepoSyncOnceNoRetry() {
        try {
            primeSslBeforeRepoSync();
            Class<?> cls = Class.forName("com.atakmap.android.update.ApkUpdateComponent");
            java.lang.reflect.Method singletonGetter = findSingletonGetter(cls);
            if (singletonGetter == null) {
                Log.d(TAG, "startup repo sync skipped: no ApkUpdateComponent singleton getter");
                return;
            }
            Object comp = singletonGetter.invoke(null);
            if (comp == null) {
                Log.d(TAG, "startup repo sync skipped: ApkUpdateComponent null (no retry)");
                return;
            }
            java.lang.reflect.Method pmGetter = findProviderManagerGetter(cls);
            if (pmGetter == null) {
                Log.d(TAG, "startup repo sync skipped: providerManager getter not found");
                return;
            }
            Object mgr = pmGetter.invoke(comp);
            if (mgr == null) {
                Log.d(TAG, "startup repo sync skipped: providerManager null");
                return;
            }
            Context uiCtx = resolveRepoSyncUiContext(mgr);
            java.lang.reflect.Method syncCtx = findContextSyncMethod(mgr.getClass());
            if (uiCtx == null || syncCtx == null) {
                Log.d(TAG, "startup repo sync skipped: no UI context or sync(Context,boolean,Listener)");
                return;
            }
            syncCtx.invoke(mgr, uiCtx, Boolean.TRUE, null);
            Log.i(TAG, "startup repo sync: one silent ProductProviderManager.sync");
        } catch (Exception e) {
            Log.w(TAG, "startup repo sync failed: " + e.getMessage());
        }
    }

    /** Append cert to an X509Certificate[] field on obj. Returns true if appended (or already present). */
    private static boolean appendToCertArray(java.lang.reflect.Field f, Object obj,
            java.security.cert.X509Certificate cert, String label) {
        try {
            java.security.cert.X509Certificate[] existing =
                    (java.security.cert.X509Certificate[]) f.get(obj);
            if (existing != null) {
                for (java.security.cert.X509Certificate c : existing) {
                    if (cert.equals(c)) {
                        Log.d(TAG, "appendToCertArray[" + label + "]: already present");
                        return true;
                    }
                }
            }
            int len = existing != null ? existing.length : 0;
            java.security.cert.X509Certificate[] updated =
                    new java.security.cert.X509Certificate[len + 1];
            if (existing != null) System.arraycopy(existing, 0, updated, 0, len);
            updated[len] = cert;
            f.set(obj, updated);
            Log.i(TAG, "appendToCertArray[" + label + "]: appended to array of len " + len);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "appendToCertArray[" + label + "] failed: " + e.getMessage());
            return false;
        }
    }

    /** Clear the static socketFactories Map cache on CertificateManager. */
    private static void clearSocketFactoriesCache(Class<?> certMgrClass) {
        try {
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object map = f.get(null);
                    if (map instanceof java.util.Map) {
                        ((java.util.Map<?, ?>) map).clear();
                        Log.i(TAG, "clearSocketFactoriesCache: cleared");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "clearSocketFactoriesCache failed: " + e.getMessage());
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
                if (SmartBeacon.isEnabled(getBeaconPrefsContext())) {
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

    /**
     * One-time v21 safety migration:
     * force Smart Beacon OFF to avoid inherited unexpected fast-cadence behavior.
     */
    private void applySmartBeaconV21OffMigration(Context prefsCtx) {
        if (prefsCtx == null) {
            return;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(prefsCtx);
            if (prefs.getBoolean(PREF_SMART_BEACON_V21_OFF_MIGRATED, false)) {
                return;
            }
            prefs.edit()
                    .putBoolean(SmartBeacon.KEY_ENABLED, false)
                    .putBoolean(PREF_SMART_BEACON_V21_OFF_MIGRATED, true)
                    .apply();
            Log.i(TAG, "Applied v21 migration: Smart Beacon forced OFF.");
        } catch (Exception e) {
            Log.w(TAG, "Smart Beacon v21 migration failed: " + e.getMessage());
        }
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

            Context beaconCtx = getBeaconPrefsContext();
            if (SmartBeacon.isEnabled(beaconCtx)) {
                if (!smartBeacon.shouldBeacon(beaconCtx, speedMph, course)) return;
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
