package com.uvpro.plugin.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.uvpro.plugin.kiss.KissFrameDecoder;
import com.uvpro.plugin.kiss.KissFrameEncoder;
import com.uvpro.plugin.protocol.PacketRouter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages Bluetooth SPP connections to BTECH UV-PRO radios.
 *
 * The BTECH UV-PRO exposes a Bluetooth SPP (Serial Port Profile) service
 * that speaks the KISS TNC protocol. Data flows:
 *
 *   Android ←(BT SPP)→ BTECH Radio ←(RF)→ Other Radios
 *
 * This class handles:
 * - Discovering paired BTECH devices
 * - Establishing SPP connections
 * - Reading incoming KISS frames in a background thread
 * - Sending outbound KISS frames
 * - Auto-reconnection on connection loss
 */
public class BtConnectionManager {

    private static final String TAG = "UVPro.BT";

    // Standard SPP UUID
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // BTECH radios often advertise with names containing these patterns
    private static final String[] BTECH_NAME_PATTERNS = {
            "UV-PRO", "BTECH", "GMRS-PRO", "UV-50PRO", "UVPRO",
            "UV-50X", "UV50", "PRO50", "BT-TNC", "TNC"
    };

    private final Context context;
    private final PacketRouter packetRouter;
    private final KissFrameDecoder kissDecoder;
    private final KissFrameEncoder kissEncoder;

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private Thread readThread;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private BluetoothDevice lastDevice;
    // Probe sockets that are already connected — reused by connect() to avoid double-connect
    private final java.util.concurrent.ConcurrentHashMap<String, BluetoothSocket> openProbeSockets =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<ConnectionListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();

    public interface ConnectionListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected(String reason);
        void onError(String error);
        void onDeviceFound(BluetoothDevice device);
        default void onScanComplete() {}
    }

    /**
     * Optional listener for raw bytes received from the radio.
     * Return true if the data was consumed and should not be processed as KISS.
     */
    public interface RawDataListener {
        boolean onRawBytes(byte[] data);
    }

    public BtConnectionManager(Context context, PacketRouter packetRouter) {
        // Use ATAK's activity context for BT operations — the plugin context
        // runs under a different package and lacks ATAK's runtime permissions.
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.kissDecoder = new KissFrameDecoder();
        this.kissEncoder = new KissFrameEncoder();
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Returns all bonded (paired) Bluetooth devices immediately.
     * Background reachability probing is handled by showDevicePicker().
     */
    public void startScan() {
        if (btAdapter == null) {
            notifyError("Bluetooth not available on this device");
            return;
        }
        if (!btAdapter.isEnabled()) {
            notifyError("Bluetooth is disabled. Please enable it.");
            return;
        }
        if (!checkBtPermissions()) {
            notifyError("Bluetooth permission denied. Grant in Settings > Apps.");
            return;
        }

        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            notifyError("No paired Bluetooth devices. Pair your radio in Android Bluetooth settings first.");
            return;
        }

        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName();
            if (name == null) name = device.getAddress();
            Log.i(TAG, "Paired device: " + name + " [" + device.getAddress() + "]");
            notifyDeviceFound(device);
        }
        notifyScanComplete();
    }

    /** Stores a probe socket that connect() can reuse to avoid a double-connect. */
    public void addProbeSocket(String address, BluetoothSocket socket) {
        openProbeSockets.put(address, socket);
    }

    /** Closes and discards all open probe sockets. */
    public void clearProbeSockets() {
        for (BluetoothSocket s : openProbeSockets.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        openProbeSockets.clear();
    }


    /**
     * Check (and request if possible) Bluetooth runtime permissions for Android 12+.
     * @return true if permissions are granted
     */
    private boolean checkBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true; // Pre-Android 12 doesn't need runtime BT permissions
        }
        boolean connectGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        boolean scanGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;

        if (connectGranted && scanGranted) {
            return true;
        }

        // Try to request permissions if we can get an Activity
        requestBtPermissions();
        return false;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            // ATAK MapView.getMapView().getContext() returns the Activity
            Context ctx = context;
            if (ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            } else {
                // Try via MapView
                com.atakmap.android.maps.MapView mv =
                        com.atakmap.android.maps.MapView.getMapView();
                if (mv != null && mv.getContext() instanceof Activity) {
                    ((Activity) mv.getContext()).requestPermissions(
                            new String[]{
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                            }, 1001);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not request BT permissions", e);
        }
    }

    /**
     * Connect to a specific Bluetooth device.
     * Tries multiple socket strategies to handle various Android BT quirks.
     */
    public void connect(BluetoothDevice device) {
        if (connected.get()) {
            disconnect();
        }
        if (connecting.getAndSet(true)) {
            Log.w(TAG, "Already connecting, ignoring duplicate request");
            return;
        }

        lastDevice = device;
        shouldReconnect.set(true);
        reconnectAttempts = 0;

        new Thread(() -> {
            try {
                String devName = device.getName() != null ? device.getName() : device.getAddress();
                Log.i(TAG, "Connecting to " + devName + "...");

                // Cancel discovery to speed up connection
                if (btAdapter.isDiscovering()) {
                    btAdapter.cancelDiscovery();
                }

                // Strategy 0: Reuse probe socket if we have one already open from startScan()
                BluetoothSocket socket = null;
                BluetoothSocket probeSocket = openProbeSockets.remove(device.getAddress());
                if (probeSocket != null && probeSocket.isConnected()) {
                    Log.i(TAG, "Reusing probe socket for " + devName);
                    socket = probeSocket;
                    // Close any other leftover probe sockets we won't use
                    for (BluetoothSocket s : openProbeSockets.values()) {
                        try { s.close(); } catch (Exception ignored) {}
                    }
                    openProbeSockets.clear();
                }

                // Strategy 1: Standard SPP UUID
                if (socket == null) {
                    socket = tryConnect(device, "SPP UUID", () ->
                            device.createRfcommSocketToServiceRecord(SPP_UUID));
                }

                // Strategy 2: Reflection-based createRfcommSocket on channel 1
                if (socket == null) {
                    socket = tryConnect(device, "RFCOMM ch1", () -> {
                        Method m = device.getClass().getMethod(
                                "createRfcommSocket", int.class);
                        return (BluetoothSocket) m.invoke(device, 1);
                    });
                }

                // Strategy 3: Insecure SPP (no encryption handshake)
                if (socket == null) {
                    socket = tryConnect(device, "Insecure SPP", () ->
                            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID));
                }

                if (socket == null) {
                    notifyError("All connection methods failed for " + devName
                            + ". Try turning the radio off/on and re-pairing.");
                    connecting.set(false);
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                    return;
                }

                btSocket = socket;
                inputStream = btSocket.getInputStream();
                outputStream = btSocket.getOutputStream();
                connected.set(true);
                connecting.set(false);
                reconnectAttempts = 0;

                // Reset decoder state from any previous partial frames
                kissDecoder.reset();

                Log.i(TAG, "Connected to " + devName);
                notifyConnected(device);

                // Start reading KISS frames
                startReadThread();

            } catch (Exception e) {
                Log.e(TAG, "Connection failed: " + e.getMessage());
                notifyError("Connection failed: " + e.getMessage());
                cleanup();
                connecting.set(false);

                // Auto-reconnect
                if (shouldReconnect.get()) {
                    scheduleReconnect();
                }
            }
        }, "BT-Connect").start();
    }

    /**
     * Try a single socket connection strategy.
     * @return Connected socket, or null if failed.
     */
    private BluetoothSocket tryConnect(BluetoothDevice device, String label,
                                       SocketFactory factory) {
        try {
            Log.d(TAG, "Trying " + label + "...");
            BluetoothSocket socket = factory.create();
            socket.connect();
            Log.i(TAG, "Connected via " + label);
            return socket;
        } catch (Exception e) {
            Log.w(TAG, label + " failed: " + e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface SocketFactory {
        BluetoothSocket create() throws Exception;
    }

    /**
     * Connect to the last known device.
     */
    public void connectToLastDevice() {
        if (lastDevice != null) {
            connect(lastDevice);
        } else {
            // Try to find a BTECH radio in paired devices
            startScan();
        }
    }

    /**
     * Disconnect from the radio.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        connecting.set(false);
        connected.set(false);
        cleanup();
        notifyDisconnected("User disconnected");
    }

    /**
     * Cancel any in-progress connect attempt and stop auto-reconnect.
     * Useful when the user explicitly wants to scan/select another device.
     */
    public void cancelConnectionAttempts() {
        shouldReconnect.set(false);
        reconnectAttempts = 0;
        connecting.set(false);
        connected.set(false);
        cleanup();
        clearProbeSockets();
        notifyDisconnected("Connection attempt cancelled");
    }

    /**
     * Send raw data through the KISS TNC to the radio.
     * The data should be an AX.25 frame (without KISS framing — we add that).
     */
    public boolean sendKissFrame(byte[] ax25Frame) {
        if (!connected.get() || outputStream == null) {
            Log.w(TAG, "Cannot send: not connected");
            return false;
        }

        try {
            byte[] kissFrame = kissEncoder.encode(ax25Frame);
            outputStream.write(kissFrame);
            outputStream.flush();
            Log.d(TAG, "Sent KISS frame: " + kissFrame.length + " bytes");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Send failed: " + e.getMessage());
            handleConnectionLost();
            return false;
        }
    }

    /**
     * Send raw bytes directly to the radio (non-KISS protocol traffic).
     */
    public boolean sendRawBytes(byte[] data) {
        if (!connected.get() || outputStream == null) {
            Log.w(TAG, "Cannot send raw bytes: not connected");
            return false;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            Log.d(TAG, "Sent raw bytes: " + data.length + " bytes");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Raw send failed: " + e.getMessage());
            handleConnectionLost();
            return false;
        }
    }

    /**
     * Background thread that continuously reads KISS frames from the radio.
     */
    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            Log.i(TAG, "Read thread started");

            while (connected.get()) {
                try {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead > 0) {
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        java.util.Arrays.fill(buffer, (byte) 0);

                        boolean consumed = false;
                        for (RawDataListener listener : rawDataListeners) {
                            try {
                                if (listener.onRawBytes(data)) {
                                    consumed = true;
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "RawDataListener failed: " + e.getMessage());
                            }
                        }
                        if (consumed) {
                            continue;
                        }

                        // KissFrameDecoder accumulates bytes and emits
                        // complete AX.25 frames when FEND delimiters are found
                        byte[][] frames = kissDecoder.decode(data);
                        for (byte[] frame : frames) {
                            Log.d(TAG, "Received AX.25 frame: " + frame.length + " bytes");
                            packetRouter.routeIncoming(frame);
                        }
                    } else if (bytesRead == -1) {
                        // Stream ended
                        handleConnectionLost();
                        break;
                    }
                } catch (IOException e) {
                    if (connected.get()) {
                        Log.e(TAG, "Read error: " + e.getMessage());
                        handleConnectionLost();
                    }
                    break;
                }
            }

            Log.i(TAG, "Read thread stopped");
        }, "BT-Read");

        readThread.setDaemon(true);
        readThread.start();
    }

    private void handleConnectionLost() {
        connected.set(false);
        cleanup();
        notifyDisconnected("Connection lost");

        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (lastDevice == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS + "). Giving up.");
            notifyError("Reconnect failed after " + MAX_RECONNECT_ATTEMPTS + " attempts. Tap Scan to retry.");
            return;
        }

        reconnectAttempts++;
        int delaySec = 5 * reconnectAttempts; // Back off: 5s, 10s, 15s...
        Log.i(TAG, "Scheduling reconnect #" + reconnectAttempts + " in " + delaySec + " seconds...");
        new Thread(() -> {
            try {
                Thread.sleep(delaySec * 1000L);
                if (shouldReconnect.get() && !connected.get() && !connecting.get()) {
                    Log.i(TAG, "Attempting reconnect #" + reconnectAttempts + "...");
                    connect(lastDevice);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "BT-Reconnect").start();
    }

    private void cleanup() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {}

        inputStream = null;
        outputStream = null;
        btSocket = null;
    }

    private boolean isBtechDevice(String name) {
        String upper = name.toUpperCase();
        for (String pattern : BTECH_NAME_PATTERNS) {
            if (upper.contains(pattern)) return true;
        }
        return false;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public String getConnectedDeviceName() {
        if (!connected.get()) return null;
        if (lastDevice != null) {
            String name = lastDevice.getName();
            return name != null ? name : lastDevice.getAddress();
        }
        return "Radio";
    }

    // --- Listener management ---

    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    public void addRawDataListener(RawDataListener listener) {
        rawDataListeners.add(listener);
    }

    public void removeRawDataListener(RawDataListener listener) {
        rawDataListeners.remove(listener);
    }

    private void notifyConnected(BluetoothDevice device) {
        for (ConnectionListener l : listeners) l.onConnected(device);
    }

    private void notifyDisconnected(String reason) {
        for (ConnectionListener l : listeners) l.onDisconnected(reason);
    }

    private void notifyError(String error) {
        for (ConnectionListener l : listeners) l.onError(error);
    }

    private void notifyDeviceFound(BluetoothDevice device) {
        for (ConnectionListener l : listeners) l.onDeviceFound(device);
    }

    private void notifyScanComplete() {
        for (ConnectionListener l : listeners) l.onScanComplete();
    }
}
