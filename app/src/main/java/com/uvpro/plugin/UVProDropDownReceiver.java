package com.uvpro.plugin;

import android.app.AlertDialog;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.GridLayout;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;

import com.uvpro.plugin.beacon.SmartBeacon;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketRouter;
import com.uvpro.plugin.radio.UVProRadioControlManager;
import com.uvpro.plugin.ui.SettingsFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UVPro Drop-Down UI Panel.
 *
 * Slides in from the right side of the ATAK map. Provides:
 * - Radio connection status and controls
 * - Contact count and statistics
 * - Quick-action buttons (beacon, ping, settings)
 * - Debug log view
 */
public class UVProDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener,
        BtConnectionManager.ConnectionListener,
        ContactTracker.ContactListener,
        PacketRouter.PacketCountListener {

    public static final String SHOW_PLUGIN =
            "com.uvpro.plugin.SHOW_PLUGIN";

    private static final String TAG = "UVPro.UI";
    private static final int MAX_LOG_LINES = 50;
    private static final int COLOR_A_ACTIVE = 0xFF00897B;       // Teal
    private static final int COLOR_A_SUBDUED = 0xFF2E6B63;      // Teal (subdued)
    private static final int COLOR_B_ACTIVE = 0xFF4CAF50;       // Bright Green
    private static final int COLOR_B_SUBDUED = 0xFF2E7D32;      // Green (subdued)
    private static final int COLOR_DIGITAL_ACTIVE = 0xFF005A8D; // Blue
    private static final int COLOR_DIGITAL_SUBDUED = 0xFF2A5674; // Blue (subdued)
    private static final int COLOR_EDIT_SELECTION_BORDER = 0xFFFF9800; // Bright orange
    private static final int EDIT_SELECTION_STROKE_DP = 3;
    private static final int COLOR_TX_HIGHLIGHT = 0xFFFF1744; // Bright red
    private static final int COLOR_TX_STROKE = 0xFFFFFFFF; // White stroke
    private static final int TARGET_A = 0;
    private static final int TARGET_B = 1;
    private static final int TARGET_DIGITAL = 2;

    private final Context pluginContext;
    private final BtConnectionManager btManager;
    private final ContactTracker contactTracker;
    private CotBridge cotBridge;
    private EncryptionManager encryptionManager;

    private View rootView;
    private View statusDot;
    private TextView statusText;
    private TextView deviceName;
    private TextView callsignText;
    private TextView contactsText;
    private TextView packetsText;
    private TextView logText;
    private TextView encryptionStatusText;
    private TextView beaconIntervalText;
    private TextView gpsBeaconIntervalLabel;
    private View rowBeaconInterval;
    private Switch switchSmartBeacon;
    private Button btnManageSmartBeaconSettings;
    private Button btnManagePluginBeaconSettings;
    private TextView teamColorText;
    private Button btnScan;
    private Button btnDisconnect;
    private Button btnLoadSelectedRepeater;
    private Button btnRefreshChannels;
    private Button btnVfoA;
    private Button btnVfoB;
    private Button btnDigital;
    private TextView selectedRepeaterText;
    private GridLayout channelsGrid;
    private Switch switchDualWatch;
    private Switch switchDigitalEdit;

    private TextView favoritesLabel;
    private HorizontalScrollView favoritesScroll;
    private LinearLayout favoritesStrip;
    private TextView connectModeHint;

    private Switch switchEncryption;
    private View passphraseRow;
    private EditText editPassphrase;
    private Button btnSetPassphrase;

    private final LinkedList<String> logLines = new LinkedList<>();
    private final CompoundButton.OnCheckedChangeListener smartBeaconCheckedListener =
            (buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                Context c = getMapView().getContext();
                SmartBeacon.setEnabled(c, isChecked);
                applySmartBeaconIntervalGreyState(isChecked);
                appendLog("Smart beacon " + (isChecked ? "on" : "off"));
                try {
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                } catch (Exception ignored) {
                }
            };
    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private int txCount = 0;
    private int rxCount = 0;
    private UVProRadioControlManager radioControlManager;
    private boolean activeVfoB = false;
    private boolean channelTargetDigital = false;
    private int selectedTarget = TARGET_A;
    private int lastAnalogTarget = TARGET_A;
    private boolean txVfoB = false;
    private int lastChannelA = -1;
    private int lastChannelB = -1;
    private int lastDigitalChannel = -1;
    private boolean lastDualWatchEnabled = false;
    private boolean lastHasRxFocus = false;
    private ValueAnimator activeVfoPulseAnimator;
    private Button pulsingVfoButton;
    private GradientDrawable pulsingVfoDrawable;
    private final AtomicBoolean snapshotReadInFlight = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRefreshPending = new AtomicBoolean(false);
    private final AtomicBoolean snapshotFullRefreshPending = new AtomicBoolean(false);
    private UVProRadioControlManager.RadioControlSnapshot lastSnapshot;

    public UVProDropDownReceiver(MapView mapView,
                                     Context pluginContext,
                                     BtConnectionManager btManager,
                                     ContactTracker contactTracker) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.btManager = btManager;
        this.contactTracker = contactTracker;

        // Register as listener for connection and contact updates
        btManager.addListener(this);
        contactTracker.setListener(this);
    }

    public void setCotBridge(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
    }

    public void setEncryptionManager(EncryptionManager encryptionManager) {
        this.encryptionManager = encryptionManager;
    }

    public void setRadioControlManager(UVProRadioControlManager radioControlManager) {
        this.radioControlManager = radioControlManager;
        if (this.radioControlManager != null) {
            this.radioControlManager.setSelectionListener(spec ->
                    getMapView().post(this::updateSelectedRepeaterUi));
            this.radioControlManager.setRadioEventListener(eventType ->
                    getMapView().post(this::refreshChannelGridAsync));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (SHOW_PLUGIN.equals(action)) {
            showDropDown(createView(),
                    HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT,
                    false, this);
        }
    }

    /**
     * Create the main plugin UI view by inflating the XML layout.
     */
    private View createView() {
        LayoutInflater inflater = LayoutInflater.from(pluginContext);
        rootView = inflater.inflate(
                pluginContext.getResources().getIdentifier(
                        "uvpro_dropdown", "layout",
                        pluginContext.getPackageName()),
                null);

        // Bind views
        bindViews();

        // Restore actual connection state (survives dropdown close/reopen)
        if (btManager.isConnected()) {
            updateConnectionUI(true, btManager.getConnectedDeviceName());
        } else {
            updateConnectionUI(false, null);
        }

        // Set callsign from ATAK self marker
        String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
        if (callsignText != null) {
            callsignText.setText(callsign);
        }

        // Make log scrollable inside the outer ScrollView
        if (logText != null) {
            logText.setMovementMethod(new ScrollingMovementMethod());
            logText.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });
        }

        // Load saved state into switches BEFORE attaching listeners
        updateStatusFields();
        // Restore packet counts in UI
        updatePacketCount();
        updateContactCount();
        // Now attach change listeners so user interactions are wired
        setupListeners();
        updateDigitalEditGuardUi();
        refreshFavoriteStrip();
        updateScanButtonText();
        updateSelectedRepeaterUi();
        refreshChannelGridFullAsync();
        refreshLogView();
        appendLog("UV-PRO ready");
        return rootView;
    }

    private void bindViews() {
        android.widget.TextView headerVersion = rootView.findViewById(getId("header_version"));
        if (headerVersion != null) {
            headerVersion.setText("v" + com.uvpro.plugin.BuildConfig.VERSION_NAME);
        }

        statusDot = rootView.findViewById(getId("status_dot"));
        statusText = rootView.findViewById(getId("status_text"));
        deviceName = rootView.findViewById(getId("device_name"));
        callsignText = rootView.findViewById(getId("text_callsign"));
        contactsText = rootView.findViewById(getId("text_contacts"));
        packetsText = rootView.findViewById(getId("text_packets"));
        logText = rootView.findViewById(getId("text_log"));
        encryptionStatusText = rootView.findViewById(getId("text_encryption_status"));
        beaconIntervalText = rootView.findViewById(getId("text_beacon_interval"));
        gpsBeaconIntervalLabel = rootView.findViewById(getId("text_gps_beacon_interval_label"));
        rowBeaconInterval = rootView.findViewById(getId("row_beacon_interval"));
        switchSmartBeacon = rootView.findViewById(getId("switch_smart_beacon"));
        btnManageSmartBeaconSettings = rootView.findViewById(getId("btn_manage_smart_beacon_settings"));
        btnManagePluginBeaconSettings = rootView.findViewById(getId("btn_manage_plugin_beacon_settings"));
        teamColorText = rootView.findViewById(getId("text_team_color"));
        btnScan = rootView.findViewById(getId("btn_scan"));
        btnDisconnect = rootView.findViewById(getId("btn_disconnect"));
        btnLoadSelectedRepeater = rootView.findViewById(getId("btn_load_selected_repeater"));
        btnRefreshChannels = rootView.findViewById(getId("btn_refresh_channels"));
        btnVfoA = rootView.findViewById(getId("btn_vfo_a"));
        btnVfoB = rootView.findViewById(getId("btn_vfo_b"));
        btnDigital = rootView.findViewById(getId("btn_digital"));
        selectedRepeaterText = rootView.findViewById(getId("text_selected_repeater"));
        channelsGrid = rootView.findViewById(getId("grid_channels"));
        switchDualWatch = rootView.findViewById(getId("switch_dual_watch"));
        switchDigitalEdit = rootView.findViewById(getId("switch_digital_edit"));

        favoritesLabel = rootView.findViewById(getId("favorites_label"));
        favoritesScroll = rootView.findViewById(getId("favorites_scroll"));
        favoritesStrip = rootView.findViewById(getId("favorites_strip"));
        connectModeHint = rootView.findViewById(getId("connect_mode_hint"));

        // Interactive switches
        switchEncryption = rootView.findViewById(getId("switch_encryption"));
        passphraseRow = rootView.findViewById(getId("passphrase_row"));
        editPassphrase = rootView.findViewById(getId("edit_passphrase"));
        btnSetPassphrase = rootView.findViewById(getId("btn_set_passphrase"));
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> onScanOrConnectClicked());

        btnDisconnect.setOnClickListener(v -> {
            btManager.disconnect();
        });

        // --- Encryption switch ---
        if (switchEncryption != null) {
            switchEncryption.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putBoolean(SettingsFragment.PREF_ENCRYPTION_ENABLED, isChecked).apply();

                if (passphraseRow != null) {
                    passphraseRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }

                if (!isChecked && encryptionManager != null) {
                    encryptionManager.setSharedSecret(null);
                    updateEncryptionStatus();
                    appendLog("Encryption disabled");
                } else if (isChecked) {
                    String existing = SettingsFragment.getEncryptionPassphrase(
                            getMapView().getContext());
                    if (existing != null && !existing.isEmpty() && encryptionManager != null) {
                        encryptionManager.setSharedSecret(existing);
                        updateEncryptionStatus();
                        appendLog("Encryption enabled (AES-256-GCM)");
                    } else {
                        updateEncryptionStatus();
                        appendLog("Configure shared secret to enable encryption");
                    }
                }
            });
        }

        if (btnSetPassphrase != null) {
            btnSetPassphrase.setOnClickListener(v -> {
                if (editPassphrase == null) return;
                String pass = editPassphrase.getText().toString().trim();
                if (pass.isEmpty()) {
                    appendLog("Shared secret cannot be empty");
                    return;
                }
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putString(SettingsFragment.PREF_ENCRYPTION_PASSPHRASE, pass).apply();

                if (encryptionManager != null) {
                    encryptionManager.setSharedSecret(pass);
                }
                editPassphrase.setText("");
                updateEncryptionStatus();
                appendLog("Shared secret saved — encryption active");
            });
        }

        // Quick action buttons
        View btnBeacon = rootView.findViewById(getId("btn_send_beacon"));
        if (btnBeacon != null) {
            btnBeacon.setOnClickListener(v -> sendManualBeacon());
        }

        View btnPing = rootView.findViewById(getId("btn_send_ping"));
        if (btnPing != null) {
            btnPing.setOnClickListener(v -> sendPing());
        }

        View btnSettings = rootView.findViewById(getId("btn_settings"));
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setOnCheckedChangeListener(smartBeaconCheckedListener);
        }
        if (btnManageSmartBeaconSettings != null) {
            btnManageSmartBeaconSettings.setOnClickListener(v ->
                    com.uvpro.plugin.beacon.SmartBeaconSettingsDialog.show(
                            getMapView().getContext(), null));
        }
        if (btnManagePluginBeaconSettings != null) {
            btnManagePluginBeaconSettings.setOnClickListener(v -> showSettingsDialog());
        }

        if (btnLoadSelectedRepeater != null) {
            btnLoadSelectedRepeater.setOnClickListener(v -> loadSelectedRepeaterToRadio());
        }

        if (btnRefreshChannels != null) {
            btnRefreshChannels.setOnClickListener(v -> refreshChannelGridFullAsync());
        }

        if (switchDualWatch != null) {
            switchDualWatch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                applyDualWatch(isChecked);
            });
            switchDualWatch.setText("");
        }

        if (btnVfoA != null) {
            btnVfoA.setOnClickListener(v -> {
                channelTargetDigital = false;
                selectedTarget = TARGET_A;
                lastAnalogTarget = TARGET_A;
                activeVfoB = false;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                rerenderGridFromLastSnapshot();
            });
            btnVfoA.setOnLongClickListener(v -> {
                applyTxSelection(false);
                return true;
            });
        }
        if (btnVfoB != null) {
            btnVfoB.setOnClickListener(v -> {
                channelTargetDigital = false;
                selectedTarget = TARGET_B;
                lastAnalogTarget = TARGET_B;
                activeVfoB = true;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                rerenderGridFromLastSnapshot();
            });
            btnVfoB.setOnLongClickListener(v -> {
                if (btnVfoB.getVisibility() != View.VISIBLE) {
                    return true;
                }
                applyTxSelection(true);
                return true;
            });
        }
        if (btnDigital != null) {
            btnDigital.setOnClickListener(v -> {
                if (!isDigitalEditArmed()) {
                    Toast.makeText(getMapView().getContext(),
                            "Enable 'Slide to edit' first.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                channelTargetDigital = true;
                selectedTarget = TARGET_DIGITAL;
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
            });
        }
        if (switchDigitalEdit != null) {
            switchDigitalEdit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!isChecked && selectedTarget == TARGET_DIGITAL) {
                    restoreAnalogEditTarget();
                }
                updateDigitalEditGuardUi();
            });
            switchDigitalEdit.setText("");
            switchDigitalEdit.setChecked(false);
        }
    }

    private void onScanOrConnectClicked() {
        if (btManager.isConnected()) {
            btManager.disconnect();
            return;
        }
        if (btManager.isConnecting()) {
            appendLog("Cancelling current connection attempt...");
            btManager.cancelConnectionAttempts();
        }
        Context ctx = getMapView().getContext();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            appendLog("Bluetooth not available");
            return;
        }

        String target = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
        BtDeviceRecord targetRecord =
                (target != null && !target.isEmpty()) ? BluetoothDeviceRegistry.find(ctx, target) : null;
        boolean connectMode = target != null && !target.isEmpty();

        if (connectMode) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(target);
                String display = targetRecord != null
                        ? BluetoothDeviceRegistry.getDisplayTitle(targetRecord)
                        : target;
                appendLog("Connecting to " + display + "...");
                btManager.connect(device);
            } catch (Exception e) {
                appendLog("Saved radio no longer available, switching to scan");
                BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
                refreshFavoriteStrip();
                updateScanButtonText();
            }
            return;
        }

        // Scan mode: never auto-select favorite target.
        foundDevices.clear();
        BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
        refreshFavoriteStrip();
        updateScanButtonText();
        appendLog("Scanning for radios...");
        btManager.startScan();
    }

    private int dip(Context c, int d) {
        return (int) (d * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateScanButtonText() {
        if (btnScan == null) return;
        Context ctx = getMapView().getContext();
        String tgt = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
        if (!btManager.isConnected() && tgt != null && !tgt.isEmpty()) {
            btnScan.setText("CONNECT");
        } else {
            btnScan.setText("SCAN & CONNECT");
        }
    }

    private void refreshFavoriteStrip() {
        if (favoritesStrip == null || favoritesScroll == null
                || favoritesLabel == null || connectModeHint == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        favoritesStrip.removeAllViews();
        List<BtDeviceRecord> favs = BluetoothDeviceRegistry.getFavoritesSorted(ctx);
        if (favs.isEmpty()) {
            favoritesLabel.setVisibility(View.GONE);
            favoritesScroll.setVisibility(View.GONE);
            connectModeHint.setVisibility(View.GONE);
            return;
        }
        favoritesLabel.setVisibility(View.VISIBLE);
        favoritesScroll.setVisibility(View.VISIBLE);
        String selected = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
        if (selected != null) {
            connectModeHint.setVisibility(View.VISIBLE);
            connectModeHint.setText(
                    "Direct connect enabled — tap the same favorite again to use Scan instead");
        } else {
            connectModeHint.setVisibility(View.GONE);
        }
        for (BtDeviceRecord r : favs) {
            Button chip = new Button(ctx);
            chip.setAllCaps(false);
            chip.setText(BluetoothDeviceRegistry.getDisplayTitle(r));
            boolean isSel = selected != null && selected.equalsIgnoreCase(r.address);
            chip.setBackgroundColor(isSel ? 0xFF00788B : 0xFF3D3D3D);
            chip.setTextColor(0xFFFFFFFF);
            int px = dip(ctx, 8);
            chip.setPadding(px, px / 2, px, px / 2);
            chip.setOnClickListener(v -> {
                String cur = BluetoothDeviceRegistry.getConnectTargetAddress(ctx);
                if (cur != null && cur.equalsIgnoreCase(r.address)) {
                    BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
                    appendLog("Using Scan & Connect mode");
                } else {
                    BluetoothDeviceRegistry.setConnectTargetAddress(ctx, r.address);
                    appendLog("Selected: "
                            + BluetoothDeviceRegistry.getDisplayTitle(r));
                }
                refreshFavoriteStrip();
                updateScanButtonText();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dip(ctx, 6));
            favoritesStrip.addView(chip, lp);
        }
    }

    private int getId(String name) {
        return pluginContext.getResources().getIdentifier(
                name, "id", pluginContext.getPackageName());
    }

    // --- Connection Listener callbacks ---

    @Override
    public void onConnected(BluetoothDevice device) {
        if (device != null) {
            BluetoothDeviceRegistry.recordConnection(getMapView().getContext(),
                    device);
        }
        String displayName = "Radio";
        if (device != null) {
            BtDeviceRecord rec = BluetoothDeviceRegistry.find(
                    getMapView().getContext(), device.getAddress());
            if (rec != null) {
                displayName = BluetoothDeviceRegistry.getDisplayTitle(rec);
            } else {
                String name = device.getName();
                displayName = name != null ? name : device.getAddress();
            }
        }
        final String finalDisplay = displayName;
        getMapView().post(() -> {
            updateConnectionUI(true, finalDisplay);
            appendLog("Connected to " + finalDisplay);
            refreshFavoriteStrip();
            updateScanButtonText();
            // Auto-populate channel grid immediately after radio connect.
            refreshChannelGridFullAsync();
            // Follow-up read: some radios return channel/settings a moment later.
            getMapView().postDelayed(this::refreshChannelGridAsync, 900L);
        });
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
    }

    @Override
    public void onDisconnected(String reason) {
        getMapView().post(() -> {
            updateConnectionUI(false, null);
            appendLog("Disconnected: " + reason);
        });
    }

    @Override
    public void onError(String error) {
        getMapView().post(() -> {
            appendLog("Error: " + error);
        });
    }

    @Override
    public void onDeviceFound(BluetoothDevice device) {
        foundDevices.add(device);
        String name = device != null ? device.getName() : "Unknown";
        getMapView().post(() -> {
            appendLog("Found: " + name);
        });
    }

    @Override
    public void onScanComplete() {
        getMapView().post(this::showDevicePicker);
    }

    // --- Contact Listener callbacks ---

    @Override
    public void onContactUpdated(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact: " + contact.getCallsign()
                    + " (" + String.format(Locale.US, "%.4f, %.4f",
                    contact.getLatitude(), contact.getLongitude()) + ")");
        });
    }

    @Override
    public void onContactRemoved(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact lost: " + contact.getCallsign());
        });
    }

    @Override
    public void onContactCountChanged(int count) {
        getMapView().post(this::updateContactCount);
    }

    // --- PacketCountListener callback ---

    @Override
    public void onPacketReceived() {
        rxCount++;
        getMapView().post(this::updatePacketCount);
    }

    public void incrementTxCount() {
        txCount++;
        getMapView().post(this::updatePacketCount);
    }

    // --- UI update methods ---

    private void updateConnectionUI(boolean connected, String device) {
        if (statusDot != null) {
            statusDot.setBackgroundColor(connected ? 0xFF4CAF50 : 0xFFFF0000);
        }
        if (statusText != null) {
            statusText.setText(connected ? "Connected" : "Disconnected");
        }
        if (deviceName != null) {
            if (connected && device != null) {
                deviceName.setText(device);
                deviceName.setVisibility(View.VISIBLE);
            } else {
                deviceName.setVisibility(View.GONE);
            }
        }
        if (btnScan != null) btnScan.setEnabled(!connected);
        if (btnDisconnect != null) btnDisconnect.setEnabled(connected);
        refreshFavoriteStrip();
        updateScanButtonText();
        if (!connected) {
            renderChannelGrid(null);
        }
    }

    private void updateContactCount() {
        if (contactsText != null) {
            int active = contactTracker.getActiveCount();
            int total = contactTracker.getTotalCount();
            contactsText.setText(active + " active / " + total + " total");
        }
    }

    private void updatePacketCount() {
        if (packetsText != null) {
            packetsText.setText(txCount + " / " + rxCount);
        }
    }

    private void updateStatusFields() {
        Context ctx = getMapView().getContext();

        boolean encOn = SettingsFragment.isEncryptionEnabled(ctx);
        if (switchEncryption != null) {
            switchEncryption.setChecked(encOn);
        }

        if (passphraseRow != null) {
            passphraseRow.setVisibility(encOn ? View.VISIBLE : View.GONE);
        }

        updateEncryptionStatus();

        // Beacon interval
        int beaconSec = SettingsFragment.getBeaconIntervalSec(ctx);
        if (beaconIntervalText != null) {
            beaconIntervalText.setText(beaconSec + "s");
        }

        boolean smartOn = SmartBeacon.isEnabled(ctx);
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setChecked(smartOn);
        }
        applySmartBeaconIntervalGreyState(smartOn);

        // Team color (ATAK preference)
        try {
            String teamColor = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (teamColorText != null) {
                teamColorText.setText(teamColor != null ? teamColor : "Cyan");
            }
        } catch (Exception ignored) {
        }
    }

    /** Dims the fixed-interval row when Smart Beacon controls the rate. */
    private void applySmartBeaconIntervalGreyState(boolean smartOn) {
        float alpha = smartOn ? 0.38f : 1.0f;
        if (rowBeaconInterval != null) {
            rowBeaconInterval.setAlpha(alpha);
        }
        int labelColor = smartOn ? 0xFF666666 : 0xFFFFFFFF;
        int valueColor = smartOn ? 0xFF6A9EAC : 0xFF00BCD4;
        if (gpsBeaconIntervalLabel != null) {
            gpsBeaconIntervalLabel.setTextColor(labelColor);
        }
        if (beaconIntervalText != null) {
            beaconIntervalText.setTextColor(valueColor);
        }
    }

    private void updateEncryptionStatus() {
        if (encryptionStatusText == null) return;
        boolean encOn = SettingsFragment.isEncryptionEnabled(getMapView().getContext());
        String pass = SettingsFragment.getEncryptionPassphrase(getMapView().getContext());
        if (encOn && pass != null && !pass.isEmpty()) {
            encryptionStatusText.setText("\u2705 AES-256-GCM active");
            encryptionStatusText.setTextColor(0xFF4CAF50);
        } else if (encOn) {
            encryptionStatusText.setText("\u26A0 Enter shared secret to activate");
            encryptionStatusText.setTextColor(0xFFFF9800);
        } else {
            encryptionStatusText.setText("All radios must use the same shared secret");
            encryptionStatusText.setTextColor(0xFF888888);
        }
    }

    private void refreshLogView() {
        if (logText != null && !logLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) {
                sb.append(l).append("\n");
            }
            logText.setText(sb.toString());
        }
    }

    private void appendLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss",
                Locale.US);
        String line = sdf.format(new Date()) + " " + message;

        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }

        refreshLogView();
        Log.d(TAG, message);
    }

    private void updateSelectedRepeaterUi() {
        if (selectedRepeaterText == null || btnLoadSelectedRepeater == null) {
            return;
        }
        UVProRadioControlManager.RepeaterSpec spec =
                radioControlManager != null ? radioControlManager.getSelectedRepeater() : null;
        if (spec == null) {
            selectedRepeaterText.setText("None selected");
            btnLoadSelectedRepeater.setEnabled(false);
            return;
        }
        selectedRepeaterText.setText(String.format(
                Locale.US, "%s (RX %.5f / TX %.5f)",
                spec.name, spec.rxFreqMHz, spec.txFreqMHz));
        btnLoadSelectedRepeater.setEnabled(true);
    }

    private void refreshChannelGridAsync() {
        refreshChannelGridAsync(false);
    }

    private void refreshChannelGridFullAsync() {
        refreshChannelGridAsync(true);
    }

    private void refreshChannelGridAsync(boolean fullSnapshot) {
        if (radioControlManager == null || channelsGrid == null) {
            return;
        }
        if (!snapshotReadInFlight.compareAndSet(false, true)) {
            snapshotRefreshPending.set(true);
            if (fullSnapshot) {
                snapshotFullRefreshPending.set(true);
            }
            return;
        }
        new Thread(() -> {
            UVProRadioControlManager.RadioControlSnapshot snapshot =
                    fullSnapshot
                            ? radioControlManager.readSnapshot(30)
                            : radioControlManager.readSnapshotFast(30);
            getMapView().post(() -> {
                try {
                    renderChannelGrid(snapshot);
                } finally {
                    snapshotReadInFlight.set(false);
                    if (snapshotRefreshPending.compareAndSet(true, false)) {
                        boolean runFull = snapshotFullRefreshPending.getAndSet(false);
                        refreshChannelGridAsync(runFull);
                    }
                }
            });
        }, "uvpro-read-channels").start();
    }

    private void rerenderGridFromLastSnapshot() {
        if (lastSnapshot != null) {
            renderChannelGrid(lastSnapshot);
            return;
        }
        updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                lastDualWatchEnabled, txVfoB, lastHasRxFocus);
    }

    private void renderChannelGrid(UVProRadioControlManager.RadioControlSnapshot snapshot) {
        if (channelsGrid == null) {
            return;
        }
        channelsGrid.removeAllViews();

        if (snapshot == null) {
            // Keep last known UI state on transient read failures while connected.
            // This avoids false flips like "dual watch off" when a single poll misses.
            if (btManager.isConnected()) {
                updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                        lastDualWatchEnabled, txVfoB, lastHasRxFocus);
                return;
            }
            // If actually disconnected, clear to baseline.
            if (switchDualWatch != null) {
                switchDualWatch.setChecked(false);
                switchDualWatch.setEnabled(false);
                switchDualWatch.setText("");
            }
            lastSnapshot = null;
            lastChannelA = -1;
            lastChannelB = -1;
            lastDigitalChannel = -1;
            lastDualWatchEnabled = false;
            lastHasRxFocus = false;
            channelTargetDigital = false;
            selectedTarget = TARGET_A;
            lastAnalogTarget = TARGET_A;
            activeVfoB = false;
            txVfoB = false;
            if (switchDigitalEdit != null) {
                switchDigitalEdit.setChecked(false);
            }
            updateVfoButtons(-1, -1, -1, false, false, false);
            return;
        }
        lastSnapshot = snapshot;

        if (switchDualWatch != null) {
            switchDualWatch.setEnabled(true);
            switchDualWatch.setChecked(snapshot.dualWatchEnabled);
            switchDualWatch.setText("");
        }

        txVfoB = snapshot.dualWatchEnabled && snapshot.activeVfoB;
        if (!snapshot.dualWatchEnabled && selectedTarget == TARGET_B) {
            selectedTarget = TARGET_A;
            channelTargetDigital = false;
            activeVfoB = false;
            lastAnalogTarget = TARGET_A;
        } else if (selectedTarget == TARGET_A) {
            activeVfoB = false;
            channelTargetDigital = false;
            lastAnalogTarget = TARGET_A;
        } else if (selectedTarget == TARGET_B) {
            activeVfoB = true;
            channelTargetDigital = false;
            lastAnalogTarget = TARGET_B;
        } else {
            if (!isDigitalEditArmed()) {
                restoreAnalogEditTarget();
            } else {
                channelTargetDigital = true;
            }
        }
        lastChannelA = snapshot.channelA;
        lastChannelB = snapshot.channelB;
        lastDigitalChannel = snapshot.digitalChannelId;
        lastDualWatchEnabled = snapshot.dualWatchEnabled;
        lastHasRxFocus = snapshot.currentChannelId >= 0;
        updateVfoButtons(snapshot.channelA, snapshot.channelB, snapshot.digitalChannelId,
                snapshot.dualWatchEnabled, txVfoB, snapshot.currentChannelId >= 0);

        for (UVProRadioControlManager.ChannelSummary channel : snapshot.channels) {
            if (channel == null) {
                continue;
            }
            Button chip = new Button(getMapView().getContext());
            chip.setAllCaps(false);
            chip.setTextSize(10f);
            chip.setMinHeight(0);
            chip.setMinimumHeight(0);
            chip.setPadding(dip(getMapView().getContext(), 4),
                    dip(getMapView().getContext(), 3),
                    dip(getMapView().getContext(), 4),
                    dip(getMapView().getContext(), 3));

            String name = (channel.name == null || channel.name.isEmpty())
                    ? "--" : channel.name;
            chip.setText(String.format(
                    Locale.US,
                    "%02d %s\n%.5f",
                    channel.channelId + 1,
                    name,
                    channel.rxFreqMHz));

            boolean activeDigital = selectedTarget == TARGET_DIGITAL;
            boolean activeA = selectedTarget == TARGET_A;
            boolean activeB = selectedTarget == TARGET_B && snapshot.dualWatchEnabled;
            boolean isA = channel.channelId == snapshot.channelA;
            // Always show B assignment in the grid (active or subdued),
            // even if B isn't currently the selected control target.
            boolean isB = channel.channelId == snapshot.channelB;
            boolean isDigital = snapshot.digitalChannelId >= 0
                    && channel.channelId == snapshot.digitalChannelId;

            int bgColor = 0xFF3D3D3D;
            // Keep B assignment always visible in green tones.
            if (isB) {
                bgColor = activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED;
            }
            if (isA) {
                bgColor = activeA ? COLOR_A_ACTIVE : COLOR_A_SUBDUED;
            }
            if (isDigital) {
                bgColor = activeDigital ? COLOR_DIGITAL_ACTIVE : COLOR_DIGITAL_SUBDUED;
            }
            // If multiple roles map to same channel, keep selected active role dominant,
            // but preserve B (green) when no active override is selected.
            if (isB && !activeA && !activeDigital) {
                bgColor = activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED;
            } else if (isA && activeA) {
                bgColor = COLOR_A_ACTIVE;
            } else if (isB) {
                // Keep B assignment green even when Digital shares same slot.
                bgColor = COLOR_B_ACTIVE;
            } else if (isDigital && activeDigital) {
                bgColor = COLOR_DIGITAL_ACTIVE;
            }

            boolean isSelectedEditChannel =
                    (selectedTarget == TARGET_A && isA)
                            || (selectedTarget == TARGET_B && snapshot.dualWatchEnabled && isB);
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setCornerRadius(dip(getMapView().getContext(), 6));
            chipBg.setColor(bgColor);
            chipBg.setStroke(
                    dip(getMapView().getContext(), isSelectedEditChannel ? EDIT_SELECTION_STROKE_DP : 1),
                    isSelectedEditChannel ? COLOR_EDIT_SELECTION_BORDER : 0x55333333);
            chip.setBackground(chipBg);
            chip.setTextColor(0xFFFFFFFF);
            chip.setOnClickListener(v -> applyChannelSelection(channel.channelId));
            chip.setOnLongClickListener(v -> {
                showChannelProgramDialog(channel);
                return true;
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2),
                    dip(getMapView().getContext(), 2));
            channelsGrid.addView(chip, lp);
        }
    }

    private void applyDualWatch(boolean enabled) {
        if (radioControlManager == null) {
            return;
        }
        appendLog("Updating dual watch...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setDualWatchEnabled(enabled);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(),
                        result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                if (result.success) {
                    lastDualWatchEnabled = enabled;
                    if (!enabled && selectedTarget == TARGET_B) {
                        selectedTarget = TARGET_A;
                        activeVfoB = false;
                        lastAnalogTarget = TARGET_A;
                    }
                    rerenderGridFromLastSnapshot();
                }
            });
        }, "uvpro-dual-watch").start();
    }

    private void applyChannelSelection(int channelId) {
        if (radioControlManager == null) {
            return;
        }
        if (channelTargetDigital) {
            appendLog(String.format(Locale.US,
                    "Setting Digital to CH%02d...", channelId + 1));
            new Thread(() -> {
                UVProRadioControlManager.ProgramResult result =
                        radioControlManager.setDigitalChannel(channelId);
                getMapView().post(() -> {
                    appendLog(result.message);
                    if (result.success) {
                        lastDigitalChannel = channelId;
                        if (switchDigitalEdit != null) {
                            switchDigitalEdit.setChecked(false);
                        }
                        restoreAnalogEditTarget();
                        rerenderGridFromLastSnapshot();
                    } else {
                        Toast.makeText(getMapView().getContext(),
                                result.message, Toast.LENGTH_LONG).show();
                    }
                });
            }, "uvpro-set-digital-channel").start();
            return;
        }
        boolean targetB = activeVfoB;
        appendLog(String.format(Locale.US,
                "Setting %s to CH%02d...",
                targetB ? "VFO-B" : "VFO-A",
                channelId + 1));
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.setWatchChannel(channelId, targetB);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    if (targetB) {
                        lastChannelB = channelId;
                    } else {
                        lastChannelA = channelId;
                    }
                    rerenderGridFromLastSnapshot();
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-channel").start();
    }

    private void setActiveVfo(boolean useVfoB) {
        if (radioControlManager == null) {
            return;
        }
        if (useVfoB && btnVfoB != null && btnVfoB.getVisibility() != View.VISIBLE) {
            return;
        }
        appendLog(useVfoB ? "Switching active side to VFO-B..." : "Switching active side to VFO-A...");
        new Thread(() -> {
            UVProRadioControlManager.ProgramResult result = radioControlManager.setActiveVfo(useVfoB);
            getMapView().post(() -> {
                appendLog(result.message);
                if (result.success) {
                    txVfoB = useVfoB;
                    rerenderGridFromLastSnapshot();
                } else {
                    Toast.makeText(getMapView().getContext(),
                            result.message, Toast.LENGTH_LONG).show();
                }
            });
        }, "uvpro-set-active-vfo").start();
    }

    private void applyTxSelection(boolean useVfoB) {
        if (useVfoB && (!lastDualWatchEnabled || (btnVfoB != null && btnVfoB.getVisibility() != View.VISIBLE))) {
            return;
        }
        channelTargetDigital = false;
        selectedTarget = useVfoB ? TARGET_B : TARGET_A;
        lastAnalogTarget = selectedTarget;
        activeVfoB = useVfoB;
        setActiveVfo(useVfoB);
    }

    private void updateVfoButtons(int channelA, int channelB, int digitalChannel,
                                  boolean dualWatchEnabled, boolean txOnB,
                                  boolean hasRxFocus) {
        if (btnVfoA == null) {
            return;
        }
        final int subduedStroke = 0x55777777;
        final boolean activeDigital = selectedTarget == TARGET_DIGITAL;
        boolean activeA = selectedTarget == TARGET_A;
        boolean activeB = selectedTarget == TARGET_B;
        if (!dualWatchEnabled && activeB) {
            activeB = false;
            activeA = true;
        }

        String aText = channelA >= 0
                ? String.format(Locale.US, "A: CH %02d", channelA + 1)
                : "A: CH --";
        btnVfoA.setText(buildVfoLabelWithTxHighlight(aText, !dualWatchEnabled || !txOnB));
        GradientDrawable aBg = buildVfoButtonBackground(
                activeA ? COLOR_A_ACTIVE : COLOR_A_SUBDUED,
                activeA ? COLOR_EDIT_SELECTION_BORDER : subduedStroke,
                activeA ? EDIT_SELECTION_STROKE_DP : 1);
        btnVfoA.setBackground(aBg);
        btnVfoA.setBackgroundTintList(null);
        btnVfoA.setTextColor(0xFFFFFFFF);
        btnVfoA.setAlpha(activeA ? 1.0f : 0.72f);
        Button activeButton = activeA ? btnVfoA : null;
        GradientDrawable activeDrawable = activeA ? aBg : null;

        if (btnVfoB != null) {
            if (dualWatchEnabled) {
                btnVfoB.setVisibility(View.VISIBLE);
                String bText = channelB >= 0
                        ? String.format(Locale.US, "B: CH %02d", channelB + 1)
                        : "B: CH --";
                btnVfoB.setText(buildVfoLabelWithTxHighlight(bText, txOnB));
                GradientDrawable bBg = buildVfoButtonBackground(
                        activeB ? COLOR_B_ACTIVE : COLOR_B_SUBDUED,
                        activeB ? COLOR_EDIT_SELECTION_BORDER : subduedStroke,
                        activeB ? EDIT_SELECTION_STROKE_DP : 1);
                btnVfoB.setBackground(bBg);
                btnVfoB.setBackgroundTintList(null);
                btnVfoB.setTextColor(0xFFFFFFFF);
                btnVfoB.setAlpha(activeB ? 1.0f : 0.72f);
                if (activeB) {
                    activeButton = btnVfoB;
                    activeDrawable = bBg;
                }
            } else {
                btnVfoB.setVisibility(View.GONE);
            }
        }
        if (btnDigital != null) {
            boolean digitalArmed = isDigitalEditArmed();
            String dText = digitalChannel >= 0
                    ? String.format(Locale.US, "Digital CH %02d", digitalChannel + 1)
                    : "Digital";
            btnDigital.setText(dText);
            GradientDrawable dBg = buildVfoButtonBackground(
                    (digitalArmed && activeDigital) ? COLOR_DIGITAL_ACTIVE : COLOR_DIGITAL_SUBDUED,
                    (digitalArmed && activeDigital) ? COLOR_EDIT_SELECTION_BORDER : subduedStroke,
                    (digitalArmed && activeDigital) ? EDIT_SELECTION_STROKE_DP : 1);
            btnDigital.setBackground(dBg);
            btnDigital.setBackgroundTintList(null);
            btnDigital.setTextColor(0xFFFFFFFF);
            btnDigital.setEnabled(digitalArmed);
            btnDigital.setAlpha(digitalArmed ? (activeDigital ? 1.0f : 0.72f) : 0.45f);
        }
        // Keep pulse visible on selected A/B edit target (never Digital),
        // independent of RX-focus so operators always see active control focus.
        updateActiveVfoPulse(activeButton, activeDrawable, !activeDigital);
    }

    private boolean isDigitalEditArmed() {
        return switchDigitalEdit != null && switchDigitalEdit.isChecked();
    }

    private void restoreAnalogEditTarget() {
        if (lastAnalogTarget == TARGET_B && lastDualWatchEnabled) {
            selectedTarget = TARGET_B;
            activeVfoB = true;
        } else {
            selectedTarget = TARGET_A;
            activeVfoB = false;
            lastAnalogTarget = TARGET_A;
        }
        channelTargetDigital = false;
    }

    private void updateDigitalEditGuardUi() {
        if (btnDigital == null) {
            return;
        }
        if (!isDigitalEditArmed() && selectedTarget == TARGET_DIGITAL) {
            restoreAnalogEditTarget();
        }
        updateVfoButtons(lastChannelA, lastChannelB, lastDigitalChannel,
                lastDualWatchEnabled, txVfoB, lastHasRxFocus);
    }

    private CharSequence buildVfoLabelWithTxHighlight(String base, boolean isTx) {
        if (!isTx) {
            return base;
        }
        final String suffix = " -TX";
        SpannableStringBuilder sb = new SpannableStringBuilder(base).append(suffix);
        int txStart = sb.length() - 2;
        int txEnd = sb.length();
        sb.setSpan(new TxBadgeSpan(),
                txStart, txEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static class TxBadgeSpan extends ReplacementSpan {
        private static final float TEXT_SCALE = 0.97f; // ~10% smaller than previous 1.08
        private static final float H_PADDING_FACTOR = 0.216f;
        private static final float V_PADDING_FACTOR = 0.108f;
        private static final float LEADING_OFFSET_FACTOR = 0.30f; // visually ~2 spaces to the right
        private static final float CORNER_RADIUS_FACTOR = 0.22f;
        private static final float STROKE_WIDTH_FACTOR = 0.12f;

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            Paint p = new Paint(paint);
            p.setTextSize(p.getTextSize() * TEXT_SCALE);
            String tx = text.subSequence(start, end).toString();
            float textW = p.measureText(tx);
            float hPad = p.getTextSize() * H_PADDING_FACTOR;
            float leadingOffset = p.getTextSize() * LEADING_OFFSET_FACTOR;
            float totalW = leadingOffset + textW + (hPad * 2f);
            if (fm != null) {
                Paint.FontMetricsInt pfm = p.getFontMetricsInt();
                int vPad = (int) (p.getTextSize() * V_PADDING_FACTOR);
                fm.ascent = pfm.ascent - vPad;
                fm.descent = pfm.descent + vPad;
                fm.top = fm.ascent;
                fm.bottom = fm.descent;
            }
            return (int) Math.ceil(totalW);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            Paint p = new Paint(paint);
            p.setAntiAlias(true);
            p.setTextSize(p.getTextSize() * TEXT_SCALE);

            String tx = text.subSequence(start, end).toString();
            float textW = p.measureText(tx);
            float hPad = p.getTextSize() * H_PADDING_FACTOR;
            float vPad = p.getTextSize() * V_PADDING_FACTOR;
            float leadingOffset = p.getTextSize() * LEADING_OFFSET_FACTOR;
            float strokeW = Math.max(1f, p.getTextSize() * STROKE_WIDTH_FACTOR);

            Paint.FontMetrics fm = p.getFontMetrics();
            float txtAscent = y + fm.ascent;
            float txtDescent = y + fm.descent;

            float left = x + leadingOffset;
            float right = left + textW + (hPad * 2f);
            float boxTop = txtAscent - vPad;
            float boxBottom = txtDescent + vPad;
            float radius = p.getTextSize() * CORNER_RADIUS_FACTOR;

            RectF box = new RectF(left, boxTop, right, boxBottom);

            // Red box with white stroke.
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_TX_HIGHLIGHT);
            canvas.drawRoundRect(box, radius, radius, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setColor(COLOR_TX_STROKE);
            canvas.drawRoundRect(box, radius, radius, p);

            float txX = left + hPad;
            // White stroke around text.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeW);
            p.setColor(COLOR_TX_STROKE);
            canvas.drawText(tx, txX, y, p);
            // Red fill text.
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_TX_HIGHLIGHT);
            canvas.drawText(tx, txX, y, p);
        }
    }

    private GradientDrawable buildVfoButtonBackground(int fillColor, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dip(getMapView().getContext(), 6));
        d.setColor(fillColor);
        d.setStroke(dip(getMapView().getContext(), strokeDp), strokeColor);
        return d;
    }

    private void updateActiveVfoPulse(Button activeButton,
                                      GradientDrawable activeDrawable,
                                      boolean shouldPulse) {
        if (!shouldPulse || activeButton == null || activeDrawable == null) {
            stopActiveVfoPulse();
            return;
        }
        if (activeVfoPulseAnimator != null && pulsingVfoButton == activeButton
                && activeVfoPulseAnimator.isRunning()) {
            return;
        }
        stopActiveVfoPulse();
        pulsingVfoButton = activeButton;
        pulsingVfoDrawable = activeDrawable;
        activeVfoPulseAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                0x66FF9800,
                0xFFFFB74D);
        activeVfoPulseAnimator.setDuration(900L);
        activeVfoPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        activeVfoPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        activeVfoPulseAnimator.addUpdateListener(animation -> {
            if (pulsingVfoDrawable == null) {
                return;
            }
            int color = (Integer) animation.getAnimatedValue();
            pulsingVfoDrawable.setStroke(dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
        });
        activeVfoPulseAnimator.start();
    }

    private void stopActiveVfoPulse() {
        if (activeVfoPulseAnimator != null) {
            activeVfoPulseAnimator.cancel();
            activeVfoPulseAnimator = null;
        }
        pulsingVfoButton = null;
        pulsingVfoDrawable = null;
    }

    private void showChannelProgramDialog(UVProRadioControlManager.ChannelSummary channel) {
        if (channel == null) {
            return;
        }
        Context ctx = getMapView().getContext();

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = dip(ctx, 12);
        layout.setPadding(p, p, p, p);

        EditText editName = new EditText(ctx);
        editName.setHint("Name (max 10)");
        editName.setSingleLine(true);
        editName.setText(channel.name == null ? "" : channel.name);
        layout.addView(editName);

        EditText editRx = new EditText(ctx);
        editRx.setHint("RX Frequency MHz (e.g. 146.940)");
        editRx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (channel.rxFreqMHz > 0) {
            editRx.setText(String.format(Locale.US, "%.5f", channel.rxFreqMHz));
        }
        layout.addView(editRx);

        EditText editTx = new EditText(ctx);
        editTx.setHint("TX Frequency MHz");
        editTx.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (channel.txFreqMHz > 0) {
            editTx.setText(String.format(Locale.US, "%.5f", channel.txFreqMHz));
        }
        layout.addView(editTx);

        EditText editTxTone = new EditText(ctx);
        editTxTone.setHint("TX Tone (blank, 100.0, D023)");
        editTxTone.setSingleLine(true);
        String txToneText = formatToneForInput(channel.txTone);
        if (!txToneText.isEmpty()) {
            editTxTone.setText(txToneText);
        }
        layout.addView(editTxTone);

        EditText editRxTone = new EditText(ctx);
        editRxTone.setHint("RX Tone (blank, 100.0, D023)");
        editRxTone.setSingleLine(true);
        String rxToneText = formatToneForInput(channel.rxTone);
        if (!rxToneText.isEmpty()) {
            editRxTone.setText(rxToneText);
        }
        layout.addView(editRxTone);

        EditText editSquelch = new EditText(ctx);
        editSquelch.setHint("Squelch 0-9 (optional)");
        editSquelch.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(editSquelch);

        CheckBox cbScan = new CheckBox(ctx);
        cbScan.setText("Scan enabled");
        cbScan.setChecked(channel.scanEnabled);
        layout.addView(cbScan);

        CheckBox cbMute = new CheckBox(ctx);
        cbMute.setText("Mute channel audio");
        cbMute.setChecked(channel.muted);
        layout.addView(cbMute);

        CheckBox cbHighPower = new CheckBox(ctx);
        cbHighPower.setText("TX high power");
        cbHighPower.setChecked(true);
        layout.addView(cbHighPower);

        CheckBox cbWide = new CheckBox(ctx);
        cbWide.setText("Wide bandwidth");
        cbWide.setChecked(true);
        layout.addView(cbWide);

        new AlertDialog.Builder(ctx)
                .setTitle(String.format(Locale.US, "Program CH%02d", channel.channelId + 1))
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = editName.getText().toString().trim();
                    String rxStr = editRx.getText().toString().trim();
                    String txStr = editTx.getText().toString().trim();
                    String txToneStr = editTxTone.getText().toString().trim();
                    String rxToneStr = editRxTone.getText().toString().trim();
                    String sqStr = editSquelch.getText().toString().trim();

                    double rx;
                    double tx;
                    try {
                        rx = Double.parseDouble(rxStr);
                        tx = Double.parseDouble(txStr);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Invalid RX/TX frequency.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Object txTone = parseToneInput(txToneStr);
                    Object rxTone = parseToneInput(rxToneStr);
                    if (!txToneStr.isEmpty() && txTone == null) {
                        Toast.makeText(ctx, "Invalid TX tone format.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!rxToneStr.isEmpty() && rxTone == null) {
                        Toast.makeText(ctx, "Invalid RX tone format.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    int sq = -1;
                    if (!sqStr.isEmpty()) {
                        try {
                            sq = Integer.parseInt(sqStr);
                        } catch (Exception e) {
                            Toast.makeText(ctx, "Invalid squelch value.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (sq < 0 || sq > 9) {
                            Toast.makeText(ctx, "Squelch must be 0-9.", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    UVProRadioControlManager.ManualChannelSpec spec =
                            new UVProRadioControlManager.ManualChannelSpec(
                                    name,
                                    rx,
                                    tx,
                                    txTone,
                                    rxTone,
                                    cbScan.isChecked(),
                                    cbMute.isChecked(),
                                    cbHighPower.isChecked(),
                                    cbWide.isChecked(),
                                    sq
                            );

                    appendLog(String.format(Locale.US, "Programming CH%02d...", channel.channelId + 1));
                    new Thread(() -> {
                        UVProRadioControlManager.ProgramResult result =
                                radioControlManager.programManualChannel(channel.channelId, spec);
                        getMapView().post(() -> {
                            appendLog(result.message);
                            Toast.makeText(ctx, result.message,
                                    result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                            if (result.success) {
                                refreshChannelGridAsync();
                            }
                        });
                    }, "uvpro-program-manual-channel").start();
                })
                .setNegativeButton("Cancel", (DialogInterface dialog, int which) -> { })
                .show();
    }

    private Object parseToneInput(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty() || "none".equalsIgnoreCase(t)) {
            return null;
        }
        if (t.startsWith("D") || t.startsWith("d")) {
            try {
                return Integer.parseInt(t.substring(1).replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return Double.parseDouble(t.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private String formatToneForInput(Object tone) {
        if (tone == null) {
            return "";
        }
        if (tone instanceof Double) {
            return String.format(Locale.US, "%.1f", (Double) tone);
        }
        if (tone instanceof Integer) {
            int v = (Integer) tone;
            if (v > 0 && v < 1000) {
                return String.format(Locale.US, "D%03d", v);
            }
            return String.valueOf(v);
        }
        return String.valueOf(tone);
    }

    private void loadSelectedRepeaterToRadio() {
        if (radioControlManager == null) {
            appendLog("Radio control unavailable");
            Toast.makeText(getMapView().getContext(),
                    "Radio control unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog("Preparing repeater load...");
        new Thread(() -> {
            if (!btManager.isConnected()) {
                appendLog("Radio not connected in plugin; attempting auto-connect...");
                btManager.connectToLastDevice();
                long startMs = System.currentTimeMillis();
                while (!btManager.isConnected()
                        && (System.currentTimeMillis() - startMs) < 8000L) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!btManager.isConnected()) {
                getMapView().post(() -> {
                    appendLog("Could not connect to radio. Use Scan & Connect, then retry.");
                    Toast.makeText(getMapView().getContext(),
                            "Radio not connected. Tap Scan & Connect.",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }

            getMapView().post(() -> appendLog("Loading selected repeater to channel 1..."));
            UVProRadioControlManager.ProgramResult result =
                    radioControlManager.programSelectedRepeaterAndTune(0);
            getMapView().post(() -> {
                appendLog(result.message);
                Toast.makeText(getMapView().getContext(),
                        result.message,
                        result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            });
        }, "uvpro-load-repeater").start();
    }

    // --- Actions ---

    private void showDevicePicker() {
        if (foundDevices.isEmpty()) {
            appendLog("No paired devices found");
            return;
        }

        Context ctx = getMapView().getContext();

        if (foundDevices.size() == 1) {
            BluetoothDevice device = foundDevices.get(0);
            String name = resolveDeviceDisplayName(ctx, device);
            appendLog("Connecting to " + name + "...");
            btManager.connect(device);
            return;
        }

        // Multiple devices — show picker with live green/gray availability dots.
        // Dialog appears instantly; dots update as background probes respond.
        final int count = foundDevices.size();
        final int[] dotColors = new int[count];
        final String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = resolveDeviceDisplayName(ctx, foundDevices.get(i));
            dotColors[i] = 0xFF888888; // gray = unknown
        }

        // Custom adapter that renders "● Name" with a colored dot
        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<String>(ctx,
                        android.R.layout.simple_list_item_1, names) {
                    @Override
                    public android.view.View getView(int pos, android.view.View cv,
                            android.view.ViewGroup parent) {
                        TextView tv = (TextView) super.getView(pos, cv, parent);
                        android.text.SpannableStringBuilder sb =
                                new android.text.SpannableStringBuilder(
                                        "\u25CF  " + names[pos]);
                        sb.setSpan(
                                new android.text.style.ForegroundColorSpan(dotColors[pos]),
                                0, 1,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        tv.setText(sb);
                        tv.setTextColor(0xFFFFFFFF);
                        tv.setTextSize(16);
                        return tv;
                    }
                };

        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("Select Radio")
                    .setAdapter(adapter, (dialog, which) -> {
                        BluetoothDevice selected = foundDevices.get(which);
                        appendLog("Connecting to " + names[which] + "...");
                        btManager.connect(selected);
                    })
                    .setNegativeButton("Cancel", (d, w) -> btManager.clearProbeSockets())
                    .setOnCancelListener(d -> btManager.clearProbeSockets())
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing device picker", e);
            appendLog("Error showing device picker");
            return;
        }

        // Background probes — update dot color when each device responds
        btManager.clearProbeSockets();
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isDiscovering()) btAdapter.cancelDiscovery();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            final BluetoothDevice device = foundDevices.get(i);
            new Thread(() -> {
                android.bluetooth.BluetoothSocket socket = null;
                try {
                    socket = device.createRfcommSocketToServiceRecord(
                            java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
                    socket.connect();
                    btManager.addProbeSocket(device.getAddress(), socket);
                    getMapView().post(() -> {
                        dotColors[idx] = 0xFF00CC44; // green = available
                        adapter.notifyDataSetChanged();
                    });
                } catch (Exception e) {
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                    getMapView().post(() -> {
                        dotColors[idx] = 0xFF884444; // dim red = unavailable
                        adapter.notifyDataSetChanged();
                    });
                }
            }, "bt-probe-" + device.getAddress()).start();
        }
    }

    /** Returns the user-assigned name for a device if one exists, otherwise the broadcast name. */
    private String resolveDeviceDisplayName(Context ctx, BluetoothDevice device) {
        try {
            BluetoothDeviceRegistry.BtDeviceRecord r =
                    BluetoothDeviceRegistry.find(ctx, device.getAddress());
            if (r != null) {
                return BluetoothDeviceRegistry.getDisplayTitle(r);
            }
        } catch (Exception ignored) {
        }
        String n = device.getName();
        return n != null ? n : device.getAddress();
    }

    private void showSettingsDialog() {
        Context ctx = getMapView().getContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // Build a custom dialog with EditTexts for key settings
        android.widget.ScrollView scrollView = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        // Bluetooth Devices — manage history, favorites, rename/delete
        TextView labelBluetooth = new TextView(ctx);
        labelBluetooth.setText("Bluetooth Radio");
        labelBluetooth.setTextColor(0xFFFFFFFF);
        labelBluetooth.setTextSize(16);
        layout.addView(labelBluetooth);

        android.widget.Button btnBluetoothDevices = new android.widget.Button(ctx);
        btnBluetoothDevices.setText("Manage Bluetooth Devices");
        btnBluetoothDevices.setOnClickListener(v ->
                com.uvpro.plugin.ui.BluetoothDevicesManagement.show(ctx, null));
        layout.addView(btnBluetoothDevices);

        // Beacon interval field (greyed out when smart beacon is on — toggle is on main panel)
        boolean smartBeaconOn = SmartBeacon.isEnabled(ctx);
        TextView labelBeacon = new TextView(ctx);
        labelBeacon.setText("\nGPS Beacon Interval (seconds)");
        labelBeacon.setTextColor(smartBeaconOn ? 0xFF666666 : 0xFFAAAAAA);
        layout.addView(labelBeacon);
        EditText editBeacon = new EditText(ctx);
        editBeacon.setText(prefs.getString(SettingsFragment.PREF_BEACON_INTERVAL,
                SettingsFragment.DEFAULT_BEACON_INTERVAL));
        editBeacon.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editBeacon.setEnabled(!smartBeaconOn);
        editBeacon.setAlpha(smartBeaconOn ? 0.35f : 1.0f);
        layout.addView(editBeacon);

        // Section header
        TextView headerMessaging = new TextView(ctx);
        headerMessaging.setText("Send messages or other ATAK data options");
        headerMessaging.setTextColor(0xFFFFFFFF);
        headerMessaging.setTextSize(15);
        headerMessaging.setPadding(0, 28, 0, 4);
        layout.addView(headerMessaging);

        // Retry interval field
        TextView labelRetryInterval = new TextView(ctx);
        labelRetryInterval.setText("\nRetry Interval (minutes) — wait before retransmitting");
        labelRetryInterval.setTextColor(0xFFAAAAAA);
        layout.addView(labelRetryInterval);
        EditText editRetryInterval = new EditText(ctx);
        editRetryInterval.setText(prefs.getString(SettingsFragment.PREF_RETRY_INTERVAL_MIN,
                SettingsFragment.DEFAULT_RETRY_INTERVAL_MIN));
        editRetryInterval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(editRetryInterval);

        // Max retries field
        TextView labelRetryMax = new TextView(ctx);
        labelRetryMax.setText("\nMax Retries — attempts before declaring failure");
        labelRetryMax.setTextColor(0xFFAAAAAA);
        layout.addView(labelRetryMax);
        EditText editRetryMax = new EditText(ctx);
        editRetryMax.setText(prefs.getString(SettingsFragment.PREF_RETRY_MAX,
                SettingsFragment.DEFAULT_RETRY_MAX));
        editRetryMax.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(editRetryMax);

        // Ping Reply
        Switch switchPingReply = new Switch(ctx);
        switchPingReply.setText("Send Ping Reply");
        switchPingReply.setTextColor(0xFFCCCCCC);
        switchPingReply.setChecked(SettingsFragment.isPingReplyEnabled(ctx));
        layout.addView(switchPingReply);

        TextView hintPingReply = new TextView(ctx);
        hintPingReply.setText("Automatically reply to incoming pings with your position.");
        hintPingReply.setTextColor(0xFF888888);
        hintPingReply.setTextSize(12);
        layout.addView(hintPingReply);

        // SA Relay — moved to bottom
        TextView labelSaRelay = new TextView(ctx);        labelSaRelay.setText("\nSA Relay");
        labelSaRelay.setTextColor(0xFFFFFFFF);
        labelSaRelay.setTextSize(16);
        layout.addView(labelSaRelay);
        Switch switchSaRelay = new Switch(ctx);
        switchSaRelay.setText("Re-broadcast TAK network positions over radio");
        switchSaRelay.setTextColor(0xFFCCCCCC);
        switchSaRelay.setChecked(SettingsFragment.isSaRelayEnabled(ctx));
        layout.addView(switchSaRelay);

        TextView hintSaRelay = new TextView(ctx);
        hintSaRelay.setText(
                "Throttled: one update per contact per 30 s. Requires TAK server + radio connected.");
        hintSaRelay.setTextColor(0xFF888888);
        hintSaRelay.setTextSize(12);
        layout.addView(hintSaRelay);

        // Team color is controlled by ATAK core settings (locationTeam). Plugin no longer overrides it.

        scrollView.addView(layout);

        new AlertDialog.Builder(ctx)
                .setTitle("UV-PRO Settings")
                .setView(scrollView)
                .setPositiveButton("Save", (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean(SettingsFragment.PREF_SA_RELAY_ENABLED,
                            switchSaRelay.isChecked());

                    prefs.edit().putBoolean(SettingsFragment.PREF_PING_REPLY_ENABLED,
                            switchPingReply.isChecked()).apply();

                    String newBeacon = editBeacon.getText().toString().trim();
                    if (!newBeacon.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_BEACON_INTERVAL, newBeacon);
                        if (beaconIntervalText != null)
                            beaconIntervalText.setText(newBeacon + "s");
                    }

                    String newRetryInterval = editRetryInterval.getText().toString().trim();
                    if (!newRetryInterval.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_RETRY_INTERVAL_MIN, newRetryInterval);
                    }

                    String newRetryMax = editRetryMax.getText().toString().trim();
                    if (!newRetryMax.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_RETRY_MAX, newRetryMax);
                    }

                    editor.apply();
                    appendLog("Settings saved");
                    appendLog("SA Relay " + (switchSaRelay.isChecked() ? "enabled" : "disabled"));
                    if (rootView != null) {
                        getMapView().post(() -> updateStatusFields());
                    }
                    try {
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent(UVProMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendManualBeacon() {
        if (cotBridge != null && btManager.isConnected()) {
            // Get self location from ATAK
            com.atakmap.android.maps.MapItem self =
                    getMapView().getSelfMarker();
            if (self != null && self instanceof com.atakmap.android.maps.PointMapItem) {
                com.atakmap.coremap.maps.coords.GeoPoint gp =
                        ((com.atakmap.android.maps.PointMapItem) self).getPoint();
                cotBridge.sendPositionOverRadio(
                        gp.getLatitude(), gp.getLongitude(),
                        gp.getAltitude(), 0, 0, -1);
                txCount++;
                updatePacketCount();
                appendLog("Beacon sent");
            } else {
                appendLog("No self-location available");
            }
        } else {
            appendLog("Not connected");
        }
    }

    private void sendPing() {
        if (cotBridge != null && btManager.isConnected()) {
            String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
            try {
                com.uvpro.plugin.protocol.UVProPacket packet =
                        com.uvpro.plugin.protocol.UVProPacket
                                .createPingPacket(com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(callsign));
                byte[] packetBytes = packet.encode();
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        appendLog("Ping encryption failed");
                        return;
                    }
                }
                com.uvpro.plugin.ax25.Ax25Frame frame =
                        com.uvpro.plugin.ax25.Ax25Frame
                                .createUVProFrame(callsign, 0, packetBytes);
                byte[] ax25 = frame.encode();
                btManager.sendKissFrame(ax25);
                txCount++;
                updatePacketCount();
                appendLog("Ping sent");
            } catch (Exception e) {
                appendLog("Ping failed: " + e.getMessage());
            }
        } else {
            appendLog("Not connected");
        }
    }

    @Override
    public void onDropDownSelectionRemoved() { }

    @Override
    public void onDropDownClose() {
        stopActiveVfoPulse();
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) { }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (!visible) {
            stopActiveVfoPulse();
        } else {
            refreshChannelGridFullAsync();
        }
    }

    @Override
    public void disposeImpl() {
        // Unregister listeners
        btManager.removeListener(this);
        contactTracker.setListener(null);
        stopActiveVfoPulse();
    }
}
