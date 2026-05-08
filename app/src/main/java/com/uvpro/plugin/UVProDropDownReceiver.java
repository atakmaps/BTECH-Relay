package com.uvpro.plugin;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
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
    private TextView saRelayStatusText;
    private TextView teamColorText;
    private Button btnScan;
    private Button btnDisconnect;
    private Button btnLoadSelectedRepeater;
    private TextView selectedRepeaterText;

    private TextView favoritesLabel;
    private HorizontalScrollView favoritesScroll;
    private LinearLayout favoritesStrip;
    private TextView connectModeHint;

    private Switch switchEncryption;
    private View passphraseRow;
    private EditText editPassphrase;
    private Button btnSetPassphrase;

    private final LinkedList<String> logLines = new LinkedList<>();
    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private int txCount = 0;
    private int rxCount = 0;
    private UVProRadioControlManager radioControlManager;

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
        refreshFavoriteStrip();
        updateScanButtonText();
        updateSelectedRepeaterUi();
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
        saRelayStatusText = rootView.findViewById(getId("text_sa_relay_status"));
        teamColorText = rootView.findViewById(getId("text_team_color"));
        btnScan = rootView.findViewById(getId("btn_scan"));
        btnDisconnect = rootView.findViewById(getId("btn_disconnect"));
        btnLoadSelectedRepeater = rootView.findViewById(getId("btn_load_selected_repeater"));
        selectedRepeaterText = rootView.findViewById(getId("text_selected_repeater"));

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

        if (btnLoadSelectedRepeater != null) {
            btnLoadSelectedRepeater.setOnClickListener(v -> loadSelectedRepeaterToRadio());
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
        boolean connectMode = targetRecord != null;

        if (connectMode) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(targetRecord.address);
                appendLog("Connecting to " + BluetoothDeviceRegistry.getDisplayTitle(targetRecord) + "...");
                btManager.connect(device);
            } catch (Exception e) {
                appendLog("Favorite radio no longer available, switching to scan");
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
        BluetoothDeviceRegistry.BtDeviceRecord rec =
                (tgt != null && !tgt.isEmpty()) ? BluetoothDeviceRegistry.find(ctx, tgt) : null;
        if (tgt != null && !tgt.isEmpty() && rec == null) {
            // Stale connect target with no registry record: revert to scan mode.
            BluetoothDeviceRegistry.setConnectTargetAddress(ctx, "");
            tgt = null;
        }
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

        boolean saOn = SettingsFragment.isSaRelayEnabled(ctx);
        if (saRelayStatusText != null) {
            saRelayStatusText.setText(saOn ? "On" : "Off");
            saRelayStatusText.setTextColor(saOn ? 0xFF4CAF50 : 0xFF888888);
        }

        // Team color (ATAK preference)
        try {
            String teamColor = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (teamColorText != null) {
                teamColorText.setText(teamColor != null ? teamColor : "Cyan");
            }
        } catch (Exception ignored) {
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

        TextView divider = new TextView(ctx);
        divider.setText(" ");
        layout.addView(divider);

        // Smart Beacon toggle
        android.widget.LinearLayout rowSmartBeacon = new android.widget.LinearLayout(ctx);
        rowSmartBeacon.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        rowSmartBeacon.setPadding(0, 20, 0, 4);

        TextView labelSmartBeacon = new TextView(ctx);
        labelSmartBeacon.setText("Smart Beacon");
        labelSmartBeacon.setTextColor(0xFFFFFFFF);
        labelSmartBeacon.setTextSize(15);
        LinearLayout.LayoutParams sbLabelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelSmartBeacon.setLayoutParams(sbLabelParams);
        rowSmartBeacon.addView(labelSmartBeacon);

        android.widget.Switch switchSmartBeacon = new android.widget.Switch(ctx);
        boolean smartBeaconOn = com.uvpro.plugin.beacon.SmartBeacon.isEnabled(ctx);
        switchSmartBeacon.setChecked(smartBeaconOn);
        rowSmartBeacon.addView(switchSmartBeacon);
        layout.addView(rowSmartBeacon);

        TextView hintSmartBeacon = new TextView(ctx);
        hintSmartBeacon.setText("Adapts beacon rate to speed and heading changes.");
        hintSmartBeacon.setTextColor(0xFFAAAAAA);
        hintSmartBeacon.setTextSize(11);
        layout.addView(hintSmartBeacon);

        // Manage Smart Beacon Settings button
        android.widget.Button btnSmartBeaconSettings = new android.widget.Button(ctx);
        btnSmartBeaconSettings.setText("Manage Smart Beacon Settings");
        btnSmartBeaconSettings.setOnClickListener(v ->
                com.uvpro.plugin.beacon.SmartBeaconSettingsDialog.show(ctx, null));
        layout.addView(btnSmartBeaconSettings);

        // Beacon interval field (greyed out when smart beacon is on)
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

        // Toggle beacon interval enabled/greyed based on switch
        switchSmartBeacon.setOnCheckedChangeListener((btn, checked) -> {
            editBeacon.setEnabled(!checked);
            editBeacon.setAlpha(checked ? 0.35f : 1.0f);
            labelBeacon.setTextColor(checked ? 0xFF666666 : 0xFFAAAAAA);
        });

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

                    com.uvpro.plugin.beacon.SmartBeacon.setEnabled(ctx, switchSmartBeacon.isChecked());
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
    public void onDropDownClose() { }

    @Override
    public void onDropDownSizeChanged(double width, double height) { }

    @Override
    public void onDropDownVisible(boolean visible) { }

    @Override
    public void disposeImpl() {
        // Unregister listeners
        btManager.removeListener(this);
        contactTracker.setListener(null);
    }
}
