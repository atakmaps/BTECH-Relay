package com.uvpro.plugin.radio;

import android.util.Log;

import com.atakmap.android.maps.MapItem;
import com.uvpro.plugin.bluetooth.BtConnectionManager;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles UV-PRO radio control commands (GAIA protocol) for repeater tuning.
 */
public class UVProRadioControlManager implements BtConnectionManager.RawDataListener {

    private static final String TAG = "UVPro.RadioCtrl";
    private static final int BASIC_GROUP = 2;
    private static final int CMD_READ_SETTINGS = 10;
    private static final int CMD_WRITE_SETTINGS = 11;
    private static final int CMD_READ_RF_CH = 13;
    private static final int CMD_WRITE_RF_CH = 14;
    private static final int CMD_GET_HT_STATUS = 20;
    private static final int CMD_REGISTER_NOTIFICATION = 6;
    private static final int CMD_EVENT_NOTIFICATION = 9;
    private static final int EVENT_HT_STATUS_CHANGED = 1;
    private static final int EVENT_HT_CH_CHANGED = 5;
    private static final int EVENT_HT_SETTINGS_CHANGED = 6;
    private static final int EVENT_RADIO_STATUS_CHANGED = 8;
    private static final int EVENT_USER_ACTION = 9;
    private static final int EVENT_SYSTEM_EVENT = 10;
    private static final int STATUS_SUCCESS = 0;

    private static final Pattern TX_FREQ_PATTERN =
            Pattern.compile("(?i)TX\\s*Frequency[^0-9]*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern RX_FREQ_PATTERN =
            Pattern.compile("(?i)RX\\s*Frequency[^0-9]*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern GENERIC_FREQ_PATTERN =
            Pattern.compile("(?i)\\bFrequency\\b[^0-9]*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern OFFSET_PATTERN =
            Pattern.compile("(?i)\\bOffset\\b[^\\-+0-9]*([\\-+]?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern TONE_PATTERN =
            Pattern.compile("(?i)(?:CTCSS\\/DCS\\s*Tone|Tone)[^A-Za-z0-9]*([A-Za-z0-9. ]+)");
    private static final Pattern FM_TONE_TABLE_PATTERN =
            Pattern.compile("(?i)FM\\s*CTCSS\\s*\\/\\s*DCS\\s*Tone\\s*:\\s*<\\/td>\\s*<td>\\s*([^<]+)");
    private static final Pattern FM_TONE_TEXT_PATTERN =
            Pattern.compile("(?i)FM\\s*CTCSS\\s*\\/\\s*DCS\\s*Tone\\s*:\\s*([A-Za-z0-9.]+)");
    private static final Pattern DCS_PATTERN =
            Pattern.compile("(?i)DCS\\s*([0-9]{2,4})");
    private static final Pattern CTCSS_PATTERN =
            Pattern.compile("(?i)CTCSS\\s*([0-9]+(?:\\.[0-9]+)?)");

    public interface SelectionListener {
        void onRepeaterSelected(RepeaterSpec spec);
    }

    public interface RadioEventListener {
        void onRadioEvent(int eventType);
    }

    public static class ProgramResult {
        public final boolean success;
        public final String message;

        public ProgramResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class ChannelSummary {
        public final int channelId;
        public final String name;
        public final double rxFreqMHz;
        public final double txFreqMHz;
        public final Object txTone;
        public final Object rxTone;
        public final boolean scanEnabled;
        public final boolean muted;

        public ChannelSummary(int channelId, String name, double rxFreqMHz, double txFreqMHz,
                              Object txTone, Object rxTone,
                              boolean scanEnabled, boolean muted) {
            this.channelId = channelId;
            this.name = name;
            this.rxFreqMHz = rxFreqMHz;
            this.txFreqMHz = txFreqMHz;
            this.txTone = txTone;
            this.rxTone = rxTone;
            this.scanEnabled = scanEnabled;
            this.muted = muted;
        }
    }

    public static class RadioControlSnapshot {
        public final int channelA;
        public final int channelB;
        public final boolean dualWatchEnabled;
        public final boolean activeVfoB;
        public final int txChannelId;
        public final int digitalChannelId;
        public final int currentChannelId;
        public final ChannelSummary[] channels;

        public RadioControlSnapshot(int channelA, int channelB, boolean dualWatchEnabled,
                                    boolean activeVfoB,
                                    int txChannelId, int digitalChannelId,
                                    int currentChannelId, ChannelSummary[] channels) {
            this.channelA = channelA;
            this.channelB = channelB;
            this.dualWatchEnabled = dualWatchEnabled;
            this.activeVfoB = activeVfoB;
            this.txChannelId = txChannelId;
            this.digitalChannelId = digitalChannelId;
            this.currentChannelId = currentChannelId;
            this.channels = channels;
        }
    }

    public static class ManualChannelSpec {
        public final String name;
        public final double rxFreqMHz;
        public final double txFreqMHz;
        public final Object txTone;
        public final Object rxTone;
        public final boolean scanEnabled;
        public final boolean muted;
        public final boolean highPower;
        public final boolean wideBandwidth;
        public final int squelchLevel; // -1 keep current, otherwise 0..9

        public ManualChannelSpec(String name,
                                 double rxFreqMHz,
                                 double txFreqMHz,
                                 Object txTone,
                                 Object rxTone,
                                 boolean scanEnabled,
                                 boolean muted,
                                 boolean highPower,
                                 boolean wideBandwidth,
                                 int squelchLevel) {
            this.name = name;
            this.rxFreqMHz = rxFreqMHz;
            this.txFreqMHz = txFreqMHz;
            this.txTone = txTone;
            this.rxTone = rxTone;
            this.scanEnabled = scanEnabled;
            this.muted = muted;
            this.highPower = highPower;
            this.wideBandwidth = wideBandwidth;
            this.squelchLevel = squelchLevel;
        }
    }

    public static class RepeaterSpec {
        public final String name;
        public final double rxFreqMHz;
        public final double txFreqMHz;
        public final Object txTone;
        public final Object rxTone;
        public final String sourceUid;

        public RepeaterSpec(String name, double rxFreqMHz, double txFreqMHz,
                            Object txTone, Object rxTone, String sourceUid) {
            this.name = name;
            this.rxFreqMHz = rxFreqMHz;
            this.txFreqMHz = txFreqMHz;
            this.txTone = txTone;
            this.rxTone = rxTone;
            this.sourceUid = sourceUid;
        }

        public String summary() {
            return String.format(Locale.US, "%s RX %.5f / TX %.5f",
                    name, rxFreqMHz, txFreqMHz);
        }
    }

    private static class CommandReply {
        int commandGroup;
        int command;
        int status = -1;
        byte[] payload = new byte[0];
    }

    private static class PendingRequest {
        int group;
        int command;
        CountDownLatch latch = new CountDownLatch(1);
        CommandReply reply;
    }

    private final BtConnectionManager btManager;
    private final Object pendingLock = new Object();
    private final Object wireLock = new Object();
    private final Object commandLock = new Object();
    private final Object snapshotCacheLock = new Object();
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private final ChannelSummary[] cachedChannels = new ChannelSummary[30];
    private int cachedChannelCount = 30;

    private PendingRequest pending;
    private RepeaterSpec selectedRepeater;
    private SelectionListener selectionListener;
    private RadioEventListener radioEventListener;
    private boolean notificationRegistrationOk = false;
    private long lastNotificationRegisterAttemptMs = 0L;

    public UVProRadioControlManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    public void start() {
        btManager.addRawDataListener(this);
    }

    public void stop() {
        btManager.removeRawDataListener(this);
        notificationRegistrationOk = false;
        synchronized (pendingLock) {
            if (pending != null) {
                pending.latch.countDown();
            }
            pending = null;
        }
    }

    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    public void setRadioEventListener(RadioEventListener listener) {
        this.radioEventListener = listener;
    }

    public RepeaterSpec getSelectedRepeater() {
        return selectedRepeater;
    }

    public void onMapItemClicked(MapItem item) {
        RepeaterSpec parsed = parseRepeater(item);
        if (parsed == null) {
            return;
        }
        selectedRepeater = parsed;
        if (selectionListener != null) {
            selectionListener.onRepeaterSelected(parsed);
        }
        Log.d(TAG, "Selected repeater from map item: " + parsed.summary());
    }

    public ProgramResult programSelectedRepeaterAndTune(int channelId) {
        RepeaterSpec spec = selectedRepeater;
        if (spec == null) {
            return new ProgramResult(false,
                    "Select a repeater marker first.");
        }
        if (!btManager.isConnected()) {
            return new ProgramResult(false,
                    "Radio not connected.");
        }
        if (channelId < 0 || channelId > 255) {
            return new ProgramResult(false, "Invalid channel ID.");
        }

        try {
            RadioChannel channel = buildChannel(spec, channelId);

            CommandReply writeChReply = sendCommandSync(
                    BASIC_GROUP, CMD_WRITE_RF_CH, channel.toBytes(), 2500);
            if (writeChReply == null) {
                return new ProgramResult(false,
                        "No response writing channel.");
            }
            if (writeChReply.status != STATUS_SUCCESS) {
                return new ProgramResult(false,
                        "Write channel failed (status 0x"
                                + String.format(Locale.US, "%02X", writeChReply.status) + ").");
            }

            CommandReply readSettings = sendCommandSync(
                    BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
            if (readSettings == null) {
                return new ProgramResult(false,
                        "No response reading settings for tune.");
            }
            if (readSettings.status != STATUS_SUCCESS || readSettings.payload.length < 12) {
                return new ProgramResult(false,
                        "Read settings failed (status 0x"
                                + String.format(Locale.US, "%02X", readSettings.status) + ").");
            }

            byte[] modified = modifyChannelInRawSettings(readSettings.payload, channelId, 0);
            if (modified == null) {
                return new ProgramResult(false, "Could not modify channel selection.");
            }

            CommandReply writeSettings = sendCommandSync(
                    BASIC_GROUP, CMD_WRITE_SETTINGS, modified, 2500);
            if (writeSettings == null) {
                return new ProgramResult(false, "No response writing settings.");
            }
            if (writeSettings.status != STATUS_SUCCESS) {
                return new ProgramResult(false,
                        "Tune failed (status 0x"
                                + String.format(Locale.US, "%02X", writeSettings.status) + ").");
            }

            return new ProgramResult(true, "Loaded " + spec.name
                    + " to channel " + (channelId + 1) + " and tuned radio.");
        } catch (Exception e) {
            Log.e(TAG, "programSelectedRepeaterAndTune failed", e);
            return new ProgramResult(false, "Programming error: " + e.getMessage());
        }
    }

    public RadioControlSnapshot readSnapshot(int maxChannels) {
        if (!btManager.isConnected()) {
            notificationRegistrationOk = false;
            return null;
        }
        maybeRegisterForStatusEvents();
        try {
            CommandReply readSettings = sendCommandSync(
                    BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
            if (readSettings == null || readSettings.status != STATUS_SUCCESS
                    || readSettings.payload == null || readSettings.payload.length < 12) {
                return null;
            }

            SettingsState settingsState = SettingsState.parse(readSettings.payload);
            if (settingsState == null) {
                return null;
            }

            boolean dualWatchEnabled = settingsState.doubleChannel != 0;
            int htChannelType = -1;
            boolean activeVfoB = settingsState.vfoX == 2;
            int txChannel = activeVfoB
                    ? settingsState.channelB
                    : settingsState.channelA;
            int currentChannel = settingsState.channelA;
            CommandReply htStatusReply = sendCommandSync(
                    BASIC_GROUP, CMD_GET_HT_STATUS, new byte[0], 2500);
            if (htStatusReply != null && htStatusReply.status == STATUS_SUCCESS
                    && htStatusReply.payload != null && htStatusReply.payload.length >= 2) {
                int parsedCurrent = parseCurrentChannelIdFromHtStatus(htStatusReply.payload);
                htChannelType = parseChannelTypeFromHtStatus(htStatusReply.payload);
                if (htChannelType == 1) {
                    activeVfoB = false;
                } else if (htChannelType == 2) {
                    activeVfoB = true;
                }
                txChannel = activeVfoB ? settingsState.channelB : settingsState.channelA;
                // Guard against occasional bogus HT status bits that point outside channel range.
                if (parsedCurrent >= 0 && parsedCurrent < 255) {
                    currentChannel = parsedCurrent;
                }
            }

            int count = Math.max(1, Math.min(30, maxChannels));
            ChannelSummary[] channels = new ChannelSummary[count];
            synchronized (snapshotCacheLock) {
                cachedChannelCount = count;
            }
            for (int i = 0; i < count; i++) {
                CommandReply channelReply = sendCommandSync(
                        BASIC_GROUP, CMD_READ_RF_CH, new byte[]{(byte) i}, 2500);
                if (channelReply == null || channelReply.status != STATUS_SUCCESS
                        || channelReply.payload == null || channelReply.payload.length < 20) {
                    channels[i] = new ChannelSummary(i, "", 0.0, 0.0,
                            null, null, false, false);
                    continue;
                }
                channels[i] = parseChannelSummary(channelReply.payload, i);
                cacheChannelSummary(channels[i]);
            }

            int channelA = normalizeToGridChannel(settingsState.channelA, count);
            int channelB = normalizeToGridChannel(settingsState.channelB, count);
            txChannel = normalizeToGridChannel(txChannel, count);
            if (txChannel < 0) {
                txChannel = channelA;
            }
            int digitalChannel = normalizeDigitalChannel(settingsState.autoShareLocCh, count);
            currentChannel = normalizeToGridChannel(currentChannel, count);

            Log.d(TAG, "snapshot settings: dual=" + dualWatchEnabled
                    + " doubleChannelRaw=" + settingsState.doubleChannel
                    + " vfoX=" + settingsState.vfoX
                    + " htChannelType=" + htChannelType
                    + " activeVfoB=" + activeVfoB
                    + " chA=" + channelA
                    + " chB=" + channelB
                    + " tx=" + txChannel
                    + " digital=" + digitalChannel
                    + " current=" + currentChannel);

            return new RadioControlSnapshot(
                    channelA,
                    channelB,
                    dualWatchEnabled,
                    activeVfoB,
                    txChannel,
                    digitalChannel,
                    currentChannel,
                    channels
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public RadioControlSnapshot readSnapshotFast(int maxChannels) {
        if (!btManager.isConnected()) {
            notificationRegistrationOk = false;
            return null;
        }
        maybeRegisterForStatusEvents();
        try {
            CommandReply readSettings = sendCommandSync(
                    BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
            if (readSettings == null || readSettings.status != STATUS_SUCCESS
                    || readSettings.payload == null || readSettings.payload.length < 12) {
                return null;
            }

            SettingsState settingsState = SettingsState.parse(readSettings.payload);
            if (settingsState == null) {
                return null;
            }

            boolean dualWatchEnabled = settingsState.doubleChannel != 0;
            int htChannelType = -1;
            boolean activeVfoB = settingsState.vfoX == 2;
            int txChannel = activeVfoB
                    ? settingsState.channelB
                    : settingsState.channelA;
            int currentChannel = settingsState.channelA;
            CommandReply htStatusReply = sendCommandSync(
                    BASIC_GROUP, CMD_GET_HT_STATUS, new byte[0], 2500);
            if (htStatusReply != null && htStatusReply.status == STATUS_SUCCESS
                    && htStatusReply.payload != null && htStatusReply.payload.length >= 2) {
                int parsedCurrent = parseCurrentChannelIdFromHtStatus(htStatusReply.payload);
                htChannelType = parseChannelTypeFromHtStatus(htStatusReply.payload);
                if (htChannelType == 1) {
                    activeVfoB = false;
                } else if (htChannelType == 2) {
                    activeVfoB = true;
                }
                txChannel = activeVfoB ? settingsState.channelB : settingsState.channelA;
                if (parsedCurrent >= 0 && parsedCurrent < 255) {
                    currentChannel = parsedCurrent;
                }
            }

            int count = Math.max(1, Math.min(30, maxChannels));
            int channelA = normalizeToGridChannel(settingsState.channelA, count);
            int channelB = normalizeToGridChannel(settingsState.channelB, count);
            txChannel = normalizeToGridChannel(txChannel, count);
            if (txChannel < 0) {
                txChannel = channelA;
            }
            int digitalChannel = normalizeDigitalChannel(settingsState.autoShareLocCh, count);
            currentChannel = normalizeToGridChannel(currentChannel, count);

            // Update only relevant channels for fast event-driven refreshes.
            ensureChannelCached(channelA, count);
            ensureChannelCached(channelB, count);
            ensureChannelCached(digitalChannel, count);
            ensureChannelCached(currentChannel, count);

            ChannelSummary[] channels = snapshotChannelsFromCache(count);
            for (int i = 0; i < channels.length; i++) {
                if (channels[i] == null) {
                    channels[i] = new ChannelSummary(i, "", 0.0, 0.0,
                            null, null, false, false);
                }
            }

            Log.d(TAG, "snapshot fast: dual=" + dualWatchEnabled
                    + " vfoX=" + settingsState.vfoX
                    + " htChannelType=" + htChannelType
                    + " activeVfoB=" + activeVfoB
                    + " chA=" + channelA
                    + " chB=" + channelB
                    + " tx=" + txChannel
                    + " digital=" + digitalChannel
                    + " current=" + currentChannel);

            return new RadioControlSnapshot(
                    channelA,
                    channelB,
                    dualWatchEnabled,
                    activeVfoB,
                    txChannel,
                    digitalChannel,
                    currentChannel,
                    channels
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public ProgramResult setDualWatchEnabled(boolean enabled) {
        if (!btManager.isConnected()) {
            return new ProgramResult(false, "Radio not connected.");
        }
        try {
            CommandReply readSettings = sendCommandSync(
                    BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
            if (readSettings == null || readSettings.status != STATUS_SUCCESS
                    || readSettings.payload == null || readSettings.payload.length < 12) {
                return new ProgramResult(false, "Could not read radio settings.");
            }
            byte[] modified = modifySettingsRaw(
                    readSettings.payload, null, null, enabled ? 1 : 0, null, null, null);
            if (modified == null) {
                return new ProgramResult(false, "Could not build dual-watch settings.");
            }
            CommandReply writeSettings = sendCommandSync(
                    BASIC_GROUP, CMD_WRITE_SETTINGS, modified, 2500);
            if (writeSettings == null) {
                return new ProgramResult(false, "No response writing settings.");
            }
            if (writeSettings.status != STATUS_SUCCESS) {
                return new ProgramResult(false, "Dual-watch update failed.");
            }
            return new ProgramResult(true, enabled ? "Dual watch enabled." : "Dual watch disabled.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProgramResult(false, "Interrupted while updating dual watch.");
        }
    }

    public ProgramResult setWatchChannel(int channelId, boolean targetVfoB) {
        if (!btManager.isConnected()) {
            return new ProgramResult(false, "Radio not connected.");
        }
        if (channelId < 0 || channelId > 255) {
            return new ProgramResult(false, "Invalid channel.");
        }
        try {
            CommandReply readSettings = sendCommandSync(
                    BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
            if (readSettings == null || readSettings.status != STATUS_SUCCESS
                    || readSettings.payload == null || readSettings.payload.length < 12) {
                return new ProgramResult(false, "Could not read radio settings.");
            }
            Integer newA = targetVfoB ? null : channelId;
            Integer newB = targetVfoB ? channelId : null;
            byte[] modified = modifySettingsRaw(readSettings.payload, newA, newB, null, null, null, null);
            if (modified == null) {
                return new ProgramResult(false, "Could not build channel settings.");
            }
            CommandReply writeSettings = sendCommandSync(
                    BASIC_GROUP, CMD_WRITE_SETTINGS, modified, 2500);
            boolean acked = writeSettings != null && writeSettings.status == STATUS_SUCCESS;
            if (!acked) {
                // Firmware sometimes applies settings but drops WRITE_SETTINGS ACKs.
                SettingsState observed = readSettingsStateForVerify();
                if (observed != null) {
                    int observedChannel = targetVfoB ? observed.channelB : observed.channelA;
                    if (channelIdMatches(observedChannel, channelId)) {
                        return new ProgramResult(true,
                                "Set " + (targetVfoB ? "VFO-B" : "VFO-A") + " to channel " + (channelId + 1) + ".");
                    }
                }
                if (writeSettings == null) {
                    return new ProgramResult(false, "No response writing channel settings.");
                }
                return new ProgramResult(false, "Failed to update channel selection.");
            }
            return new ProgramResult(true,
                    "Set " + (targetVfoB ? "VFO-B" : "VFO-A") + " to channel " + (channelId + 1) + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProgramResult(false, "Interrupted while setting channel.");
        }
    }

    public ProgramResult setActiveVfo(boolean useVfoB) {
        if (!btManager.isConnected()) {
            return new ProgramResult(false, "Radio not connected.");
        }
        try {
            CommandReply readSettings = sendCommandSync(
                    BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
            if (readSettings == null || readSettings.status != STATUS_SUCCESS
                    || readSettings.payload == null || readSettings.payload.length < 12) {
                return new ProgramResult(false, "Could not read radio settings.");
            }
            SettingsState state = SettingsState.parse(readSettings.payload);
            if (state == null) {
                return new ProgramResult(false, "Could not parse radio settings.");
            }
            // On these radios, dual-watch selector bits are what actually flips A/B TX side.
            // 0 = off, 1 = A, 2 = B. Preserve off if dual-watch is currently disabled.
            int desiredDoubleMode = (state.doubleChannel == 0) ? 0 : (useVfoB ? 2 : 1);
            ProgramResult primary = writeActiveVfoDoubleMode(readSettings.payload, desiredDoubleMode);
            if (!primary.success) {
                Log.w(TAG, "setActiveVfo primary write did not ack: " + primary.message);
            }
            // Some radios apply TX side changes even when a WRITE_SETTINGS reply times out.
            // Always verify real radio state before declaring failure.
            Boolean observedAfterPrimaryWrite = queryActiveVfoBFromHtStatus();
            if (observedAfterPrimaryWrite != null && observedAfterPrimaryWrite == useVfoB) {
                return useVfoB
                        ? new ProgramResult(true, "Active VFO set to B (TX now follows B channel).")
                        : new ProgramResult(true, "Active VFO set to A.");
            }
            // Fallback for firmware variants that still key off vfoX.
            Boolean observedAfterPrimary = queryActiveVfoBFromHtStatus();
            if (observedAfterPrimary != null && observedAfterPrimary != useVfoB) {
                int fallbackVfoX = useVfoB ? 1 : 0;
                ProgramResult fallback = writeActiveVfoMode(readSettings.payload, fallbackVfoX);
                if (fallback.success) {
                    Boolean observedAfterFallback = queryActiveVfoBFromHtStatus();
                    if (observedAfterFallback == null || observedAfterFallback == useVfoB) {
                        Log.d(TAG, "setActiveVfo fallback encoding worked: vfoX=" + fallbackVfoX);
                    } else {
                        Log.w(TAG, "setActiveVfo fallback encoding did not change HT status side.");
                    }
                }
            }
            Boolean observedFinal = queryActiveVfoBFromHtStatus();
            if (observedFinal != null && observedFinal != useVfoB) {
                return new ProgramResult(false, "Radio did not accept TX side change.");
            }
            if (observedFinal == null && !primary.success) {
                // Surface a clearer message only when we have neither ack nor status confirmation.
                return new ProgramResult(false, "No response setting VFO mode.");
            }
            if (useVfoB) {
                return new ProgramResult(true, "Active VFO set to B (TX now follows B channel).");
            } else {
                return new ProgramResult(true, "Active VFO set to A.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProgramResult(false, "Interrupted while switching active VFO.");
        }
    }

    private ProgramResult writeActiveVfoMode(byte[] rawSettingsPayload, int vfoX) throws InterruptedException {
        byte[] modified = modifySettingsRaw(rawSettingsPayload, null, null, null, null, null, vfoX);
        if (modified == null) {
            return new ProgramResult(false, "Could not build active VFO settings.");
        }
        CommandReply writeSettings = sendCommandSync(
                BASIC_GROUP, CMD_WRITE_SETTINGS, modified, 2500);
        if (writeSettings == null) {
            return new ProgramResult(false, "No response writing active VFO.");
        }
        if (writeSettings.status != STATUS_SUCCESS) {
            return new ProgramResult(false, "Failed to switch active VFO.");
        }
        return new ProgramResult(true, "ok");
    }

    private ProgramResult writeActiveVfoDoubleMode(byte[] rawSettingsPayload, int doubleMode)
            throws InterruptedException {
        byte[] modified = modifySettingsRaw(rawSettingsPayload, null, null, doubleMode, null, null, null);
        if (modified == null) {
            return new ProgramResult(false, "Could not build active VFO mode settings.");
        }
        CommandReply writeSettings = sendCommandSync(
                BASIC_GROUP, CMD_WRITE_SETTINGS, modified, 2500);
        if (writeSettings == null) {
            return new ProgramResult(false, "No response writing active VFO mode.");
        }
        if (writeSettings.status != STATUS_SUCCESS) {
            return new ProgramResult(false, "Failed to switch active VFO mode.");
        }
        return new ProgramResult(true, "ok");
    }

    private Boolean queryActiveVfoBFromHtStatus() throws InterruptedException {
        CommandReply htStatusReply = sendCommandSync(
                BASIC_GROUP, CMD_GET_HT_STATUS, new byte[0], 2500);
        if (htStatusReply == null || htStatusReply.status != STATUS_SUCCESS
                || htStatusReply.payload == null || htStatusReply.payload.length < 1) {
            return null;
        }
        int channelType = parseChannelTypeFromHtStatus(htStatusReply.payload);
        if (channelType == 2) {
            return Boolean.TRUE;
        }
        if (channelType == 1) {
            return Boolean.FALSE;
        }
        return null;
    }

    private SettingsState readSettingsStateForVerify() throws InterruptedException {
        CommandReply verifySettings = sendCommandSync(
                BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
        if (verifySettings == null || verifySettings.status != STATUS_SUCCESS
                || verifySettings.payload == null || verifySettings.payload.length < 12) {
            return null;
        }
        return SettingsState.parse(verifySettings.payload);
    }

    private boolean channelIdMatches(int observedRaw, int expectedChannelId) {
        return observedRaw == expectedChannelId || observedRaw == (expectedChannelId + 1);
    }

    private boolean isManualChannelWriteApplied(int channelId, ManualChannelSpec spec)
            throws InterruptedException {
        CommandReply readChannel = sendCommandSync(
                BASIC_GROUP, CMD_READ_RF_CH, new byte[]{(byte) channelId}, 2500);
        if (readChannel == null || readChannel.status != STATUS_SUCCESS
                || readChannel.payload == null || readChannel.payload.length < 16) {
            return false;
        }
        ChannelSummary observed = parseChannelSummary(readChannel.payload, channelId);
        if (observed == null) {
            return false;
        }
        // 100 Hz tolerance avoids false negatives from firmware-side rounding.
        return Math.abs(observed.rxFreqMHz - spec.rxFreqMHz) <= 0.0001
                && Math.abs(observed.txFreqMHz - spec.txFreqMHz) <= 0.0001;
    }

    public ProgramResult programManualChannel(int channelId, ManualChannelSpec spec) {
        if (!btManager.isConnected()) {
            return new ProgramResult(false, "Radio not connected.");
        }
        if (spec == null) {
            return new ProgramResult(false, "Channel parameters are missing.");
        }
        if (channelId < 0 || channelId > 255) {
            return new ProgramResult(false, "Invalid channel.");
        }
        if (spec.rxFreqMHz <= 0.0 || spec.txFreqMHz <= 0.0) {
            return new ProgramResult(false, "RX/TX frequency must be > 0.");
        }
        try {
            RadioChannel channel = new RadioChannel(
                    channelId,
                    0, // FM
                    spec.txFreqMHz,
                    0, // FM
                    spec.rxFreqMHz,
                    spec.txTone,
                    spec.rxTone,
                    spec.scanEnabled,
                    spec.highPower,
                    false, // talk-around
                    spec.wideBandwidth ? 1 : 0,
                    false, // pre/de-emphasis bypass
                    false, // sign
                    !spec.highPower, // med power when not high
                    false, // tx disable
                    false, // fixed freq
                    false, // fixed bandwidth
                    false, // fixed tx power
                    spec.muted,
                    sanitizeName(spec.name)
            );

            CommandReply writeChannel = sendCommandSync(
                    BASIC_GROUP, CMD_WRITE_RF_CH, channel.toBytes(), 2500);
            boolean writeAcked = writeChannel != null && writeChannel.status == STATUS_SUCCESS;
            if (!writeAcked) {
                if (!isManualChannelWriteApplied(channelId, spec)) {
                    if (writeChannel == null) {
                        return new ProgramResult(false, "No response writing channel.");
                    }
                    return new ProgramResult(false, "Write channel failed.");
                }
            }

            if (spec.squelchLevel >= 0) {
                CommandReply readSettings = sendCommandSync(
                        BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
                if (readSettings == null || readSettings.status != STATUS_SUCCESS
                        || readSettings.payload == null || readSettings.payload.length < 12) {
                    return new ProgramResult(false, "Channel saved, but failed to read settings for squelch.");
                }
                int clamped = Math.max(0, Math.min(9, spec.squelchLevel));
                byte[] modified = modifySettingsRaw(
                        readSettings.payload, null, null, null, clamped, null, null);
                if (modified == null) {
                    return new ProgramResult(false, "Channel saved, but failed to build squelch update.");
                }
                CommandReply writeSettings = sendCommandSync(
                        BASIC_GROUP, CMD_WRITE_SETTINGS, modified, 2500);
                if (writeSettings == null || writeSettings.status != STATUS_SUCCESS) {
                    return new ProgramResult(false, "Channel saved, but failed to apply squelch.");
                }
            }

            return new ProgramResult(true, "Channel " + (channelId + 1) + " saved.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProgramResult(false, "Interrupted while programming channel.");
        } catch (Exception e) {
            return new ProgramResult(false, "Programming error: " + e.getMessage());
        }
    }

    public ProgramResult setDigitalChannel(int channelId) {
        if (!btManager.isConnected()) {
            return new ProgramResult(false, "Radio not connected.");
        }
        if (channelId < 0 || channelId >= 30) {
            return new ProgramResult(false, "Invalid digital channel.");
        }
        try {
            CommandReply readSettings = sendCommandSync(
                    BASIC_GROUP, CMD_READ_SETTINGS, new byte[0], 2500);
            if (readSettings == null || readSettings.status != STATUS_SUCCESS
                    || readSettings.payload == null || readSettings.payload.length < 12) {
                return new ProgramResult(false, "Could not read radio settings.");
            }
            // Digital channel field behaves as channel-numbered (1..30) on these radios.
            int encodedDigitalChannel = channelId + 1;
            byte[] modified = modifySettingsRaw(
                    readSettings.payload, null, null, null, null, encodedDigitalChannel, null);
            if (modified == null) {
                return new ProgramResult(false, "Could not build digital channel settings.");
            }
            CommandReply writeSettings = sendCommandSync(
                    BASIC_GROUP, CMD_WRITE_SETTINGS, modified, 2500);
            if (writeSettings == null) {
                return new ProgramResult(false, "No response writing digital channel.");
            }
            if (writeSettings.status != STATUS_SUCCESS) {
                return new ProgramResult(false, "Failed to update digital channel.");
            }
            return new ProgramResult(true, "Digital channel set to CH" + String.format(Locale.US, "%02d", channelId + 1) + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProgramResult(false, "Interrupted while setting digital channel.");
        }
    }

    private void ensureChannelCached(int channelId, int count) throws InterruptedException {
        if (channelId < 0 || channelId >= count) {
            return;
        }
        synchronized (snapshotCacheLock) {
            cachedChannelCount = count;
            if (cachedChannels[channelId] != null) {
                return;
            }
        }
        CommandReply channelReply = sendCommandSync(
                BASIC_GROUP, CMD_READ_RF_CH, new byte[]{(byte) channelId}, 2500);
        if (channelReply == null || channelReply.status != STATUS_SUCCESS
                || channelReply.payload == null || channelReply.payload.length < 20) {
            return;
        }
        ChannelSummary parsed = parseChannelSummary(channelReply.payload, channelId);
        cacheChannelSummary(parsed);
    }

    private ChannelSummary[] snapshotChannelsFromCache(int count) {
        ChannelSummary[] channels = new ChannelSummary[count];
        synchronized (snapshotCacheLock) {
            cachedChannelCount = count;
            System.arraycopy(cachedChannels, 0, channels, 0, Math.min(count, cachedChannels.length));
        }
        return channels;
    }

    private void cacheChannelSummary(ChannelSummary summary) {
        if (summary == null || summary.channelId < 0 || summary.channelId >= cachedChannels.length) {
            return;
        }
        synchronized (snapshotCacheLock) {
            cachedChannels[summary.channelId] = summary;
        }
    }

    @Override
    public boolean onRawBytes(byte[] data) {
        if (data == null || data.length < 2) {
            return false;
        }
        if (data[0] != (byte) 0xFF) {
            return false;
        }
        synchronized (wireLock) {
            try {
                receiveBuffer.write(data);
                drainFrames();
            } catch (Exception e) {
                Log.w(TAG, "Failed to process raw radio data: " + e.getMessage());
                receiveBuffer.reset();
            }
        }
        return true;
    }

    private void drainFrames() {
        byte[] all = receiveBuffer.toByteArray();
        int offset = 0;
        while (offset + 4 <= all.length) {
            if (all[offset] != (byte) 0xFF || all[offset + 1] != 0x01) {
                offset++;
                continue;
            }
            int payloadLen = all[offset + 3] & 0xFF;
            int frameLen = 4 + payloadLen + 4;
            if (offset + frameLen > all.length) {
                break;
            }
            byte[] frame = Arrays.copyOfRange(all, offset, offset + frameLen);
            handleGaiaFrame(frame);
            offset += frameLen;
        }
        byte[] remaining = Arrays.copyOfRange(all, offset, all.length);
        receiveBuffer.reset();
        if (remaining.length > 0) {
            receiveBuffer.write(remaining, 0, remaining.length);
        }
    }

    private void handleGaiaFrame(byte[] frame) {
        if (frame.length < 8) {
            return;
        }
        byte[] message = Arrays.copyOfRange(frame, 4, frame.length);
        ProtocolMessage pm;
        try {
            pm = ProtocolMessage.parse(message);
        } catch (Exception e) {
            Log.w(TAG, "Discarding invalid GAIA frame");
            return;
        }
        if (!pm.isReply) {
            if (pm.commandGroup == BASIC_GROUP && pm.command == CMD_EVENT_NOTIFICATION
                    && pm.bodyBytes.length > 0) {
                int eventType = pm.bodyBytes[0] & 0xFF;
                Log.d(TAG, "radio event notification: type=" + eventType);
                RadioEventListener listener = radioEventListener;
                if (listener != null) {
                    try {
                        listener.onRadioEvent(eventType);
                    } catch (Exception ignored) {
                    }
                }
            }
            return;
        }
        CommandReply reply = new CommandReply();
        reply.commandGroup = pm.commandGroup;
        reply.command = pm.command;
        if (pm.bodyBytes.length > 0) {
            reply.status = pm.bodyBytes[0] & 0xFF;
            if (pm.bodyBytes.length > 1) {
                reply.payload = Arrays.copyOfRange(pm.bodyBytes, 1, pm.bodyBytes.length);
            }
        } else {
            reply.status = -1;
            reply.payload = new byte[0];
        }

        synchronized (pendingLock) {
            if (pending != null && pending.group == reply.commandGroup
                    && pending.command == reply.command) {
                pending.reply = reply;
                pending.latch.countDown();
            }
        }
    }

    private CommandReply sendCommandSync(int commandGroup, int command, byte[] body, long timeoutMs)
            throws InterruptedException {
        synchronized (commandLock) {
            PendingRequest req = new PendingRequest();
            req.group = commandGroup;
            req.command = command;

            synchronized (pendingLock) {
                pending = req;
            }

            byte[] commandBytes = createRadioCommand(commandGroup, command, body);
            if (!btManager.sendRawBytes(commandBytes)) {
                synchronized (pendingLock) {
                    pending = null;
                }
                return null;
            }

            boolean ok = req.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            synchronized (pendingLock) {
                if (pending == req) {
                    pending = null;
                }
            }
            return ok ? req.reply : null;
        }
    }

    private void maybeRegisterForStatusEvents() {
        if (notificationRegistrationOk) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - lastNotificationRegisterAttemptMs) < 2500L) {
            return;
        }
        lastNotificationRegisterAttemptMs = now;
        try {
            if (!registerEvent(EVENT_HT_STATUS_CHANGED)) {
                return;
            }
            if (!registerEvent(EVENT_HT_SETTINGS_CHANGED)) {
                return;
            }
            if (!registerEvent(EVENT_HT_CH_CHANGED)) {
                return;
            }
            registerEvent(EVENT_RADIO_STATUS_CHANGED);
            registerEvent(EVENT_USER_ACTION);
            registerEvent(EVENT_SYSTEM_EVENT);
            notificationRegistrationOk = true;
            Log.d(TAG, "registered radio event notifications");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean registerEvent(int eventType) throws InterruptedException {
        CommandReply reply = sendCommandSync(
                BASIC_GROUP, CMD_REGISTER_NOTIFICATION, new byte[]{(byte) eventType}, 1200);
        // REGISTER_NOTIFICATION can report non-zero-but-not-failure status on some firmware.
        // Treat only explicit failure (0xFF) and null timeout as failure.
        return reply != null && reply.status != 0xFF;
    }

    private RepeaterSpec parseRepeater(MapItem item) {
        if (item == null) {
            return null;
        }

        String title = firstNonEmpty(
                safeMeta(item, "title"),
                safeMeta(item, "callsign"),
                safeMeta(item, "name"),
                item.getUID());

        StringBuilder text = new StringBuilder();
        appendIfPresent(text, safeMeta(item, "description"));
        appendIfPresent(text, safeMeta(item, "remarks"));
        appendIfPresent(text, safeMeta(item, "summary"));
        appendIfPresent(text, safeMeta(item, "comment"));
        appendIfPresent(text, safeMeta(item, "takv"));
        appendIfPresent(text, safeMeta(item, "freq"));
        appendIfPresent(text, safeMeta(item, "frequency"));
        appendIfPresent(text, safeMeta(item, "tag"));

        String all = text.toString();
        if (all.isEmpty()) {
            return null;
        }

        boolean hasRepeaterSignals = all.toLowerCase(Locale.US).contains("#repeater")
                || all.toLowerCase(Locale.US).contains("tx frequency")
                || all.toLowerCase(Locale.US).contains("rx frequency")
                || all.toLowerCase(Locale.US).contains("ctcss")
                || all.toLowerCase(Locale.US).contains("dcs");
        if (!hasRepeaterSignals) {
            return null;
        }

        Double rx = extractFrequencyMHz(all, RX_FREQ_PATTERN);
        if (rx == null) {
            rx = extractFrequencyMHz(all, GENERIC_FREQ_PATTERN);
        }
        Double tx = extractFrequencyMHz(all, TX_FREQ_PATTERN);
        if (tx == null) {
            Double offset = extractOffset(all);
            if (offset != null && rx != null) {
                tx = rx + offset;
            }
        }
        if (rx == null || tx == null) {
            return null;
        }

        Object tone = extractTone(all);
        String shortName = sanitizeName(title);
        RepeaterSpec spec = new RepeaterSpec(shortName, rx, tx, tone, null, item.getUID());
        Log.d(TAG, "Parsed repeater: " + spec.summary() + ", txTone=" + formatTone(spec.txTone));
        return spec;
    }

    private static String safeMeta(MapItem item, String key) {
        try {
            return item.getMetaString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(value);
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "REPEATER";
    }

    private static String sanitizeName(String input) {
        String n = input == null ? "REPEATER" : input.trim();
        if (n.isEmpty()) {
            n = "REPEATER";
        }
        n = n.replaceAll("[^A-Za-z0-9\\- ]", "");
        if (n.length() > 10) {
            n = n.substring(0, 10);
        }
        return n;
    }

    private static Double extractFrequencyMHz(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        return normalizeFreq(m.group(1));
    }

    private static Double normalizeFreq(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (v > 10000.0) {
                return v / 1_000_000.0;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static Double extractOffset(String text) {
        Matcher m = OFFSET_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private static Object extractTone(String text) {
        Matcher fmTable = FM_TONE_TABLE_PATTERN.matcher(text);
        if (fmTable.find()) {
            Object tone = parseToneToken(fmTable.group(1));
            if (tone != null) return tone;
        }
        Matcher fmText = FM_TONE_TEXT_PATTERN.matcher(text);
        if (fmText.find()) {
            Object tone = parseToneToken(fmText.group(1));
            if (tone != null) return tone;
        }

        Matcher dcs = DCS_PATTERN.matcher(text);
        if (dcs.find()) {
            try {
                return Integer.parseInt(dcs.group(1));
            } catch (Exception ignored) {
            }
        }
        Matcher ctcss = CTCSS_PATTERN.matcher(text);
        if (ctcss.find()) {
            try {
                return Double.parseDouble(ctcss.group(1));
            } catch (Exception ignored) {
            }
        }
        Matcher generic = TONE_PATTERN.matcher(text);
        if (!generic.find()) {
            return null;
        }
        return parseToneToken(generic.group(1));
    }

    private static Object parseToneToken(String token) {
        if (token == null) return null;
        String g = token.trim();
        if (g.equalsIgnoreCase("none") || g.equalsIgnoreCase("no tone")) {
            return null;
        }
        if (g.equalsIgnoreCase("restricted") || g.equalsIgnoreCase("private")) {
            return null;
        }
        if (g.startsWith("D") || g.startsWith("d")) {
            try {
                return Integer.parseInt(g.substring(1).replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
            }
        }
        try {
            if (g.contains(".")) {
                return Double.parseDouble(g.replaceAll("[^0-9.]", ""));
            }
            int i = Integer.parseInt(g.replaceAll("[^0-9]", ""));
            if (i >= 6700) {
                return i / 100.0;
            }
            return i;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String formatTone(Object tone) {
        if (tone == null) return "none";
        if (tone instanceof Double) return String.format(Locale.US, "%.1f", (Double) tone);
        return String.valueOf(tone);
    }

    private static RadioChannel buildChannel(RepeaterSpec spec, int channelId) {
        return new RadioChannel(
                channelId,
                0, // FM
                spec.txFreqMHz,
                0, // FM
                spec.rxFreqMHz,
                spec.txTone,
                spec.rxTone,
                true,  // scan
                true,  // tx max power
                false, // talk around
                1,     // wide bandwidth
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                spec.name
        );
    }

    private static byte[] modifyChannelInRawSettings(byte[] rawSettings, int channelId, int dualWatchMode) {
        Integer newA = null;
        Integer newB = null;
        if (dualWatchMode == 2) {
            newB = channelId;
        } else {
            newA = channelId;
        }
        return modifySettingsRaw(rawSettings, newA, newB, null, null, null, null);
    }

    private static byte[] modifySettingsRaw(byte[] rawSettings,
                                            Integer newChannelA,
                                            Integer newChannelB,
                                            Integer newDoubleChannel,
                                            Integer newSquelch,
                                            Integer newDigitalChannel,
                                            Integer newVfoX) {
        try {
            if (rawSettings.length < 12) {
                return null;
            }

            BitReader reader = new BitReader(rawSettings);
            int channelALower = reader.readInt(4);
            int channelBLower = reader.readInt(4);
            boolean scan = reader.readBool();
            int aghfpCallMode = reader.readInt(1);
            int doubleChannel = reader.readInt(2);
            int squelchLevel = reader.readInt(4);
            boolean tailElim = reader.readBool();
            boolean autoRelayEn = reader.readBool();
            boolean autoPowerOn = reader.readBool();
            boolean keepAghfpLink = reader.readBool();
            int micGain = reader.readInt(3);
            int txHoldTime = reader.readInt(4);
            int txTimeLimit = reader.readInt(5);
            int localSpeaker = reader.readInt(2);
            int btMicGain = reader.readInt(3);
            boolean adaptiveResponse = reader.readBool();
            boolean disTone = reader.readBool();
            boolean powerSavingMode = reader.readBool();
            int autoPowerOff = reader.readInt(3);
            int autoShareLocCh = reader.readInt(5);
            int hmSpeaker = reader.readInt(2);
            int positioningSystem = reader.readInt(4);
            int timeOffset = reader.readInt(6);
            boolean useFreqRange2 = reader.readBool();
            boolean pttLock = reader.readBool();
            boolean leadingSyncBitEn = reader.readBool();
            boolean pairingAtPowerOn = reader.readBool();
            int screenTimeout = reader.readInt(5);
            int vfoX = reader.readInt(2);
            boolean imperialUnit = reader.readBool();
            int channelAUpper = reader.readInt(4);
            int channelBUpper = reader.readInt(4);
            int wxMode = reader.readInt(2);
            int noaaCh = reader.readInt(4);
            int vfolTxPowerX = reader.readInt(2);
            int vfo2TxPowerX = reader.readInt(2);
            boolean disDigitalMute = reader.readBool();
            boolean signalingEccEn = reader.readBool();
            boolean chDataLock = reader.readBool();
            reader.skipBits(3);

            int currentA = (channelAUpper << 4) | channelALower;
            int currentB = (channelBUpper << 4) | channelBLower;
            int nextA = newChannelA != null ? newChannelA : currentA;
            int nextB = newChannelB != null ? newChannelB : currentB;
            int nextDouble = newDoubleChannel != null ? newDoubleChannel : doubleChannel;
            int nextSquelch = newSquelch != null ? newSquelch : squelchLevel;
            int nextDigitalChannel = newDigitalChannel != null ? newDigitalChannel : autoShareLocCh;
            int nextVfoX = newVfoX != null ? newVfoX : vfoX;

            BitWriter writer = new BitWriter(12);
            writer.writeInt(nextA & 0x0F, 4);
            writer.writeInt(nextB & 0x0F, 4);
            writer.writeBool(scan);
            writer.writeInt(aghfpCallMode, 1);
            writer.writeInt(nextDouble, 2);
            writer.writeInt(nextSquelch, 4);
            writer.writeBool(tailElim);
            writer.writeBool(autoRelayEn);
            writer.writeBool(autoPowerOn);
            writer.writeBool(keepAghfpLink);
            writer.writeInt(micGain, 3);
            writer.writeInt(txHoldTime, 4);
            writer.writeInt(txTimeLimit, 5);
            writer.writeInt(localSpeaker, 2);
            writer.writeInt(btMicGain, 3);
            writer.writeBool(adaptiveResponse);
            writer.writeBool(disTone);
            writer.writeBool(powerSavingMode);
            writer.writeInt(autoPowerOff, 3);
            writer.writeInt(nextDigitalChannel, 5);
            writer.writeInt(hmSpeaker, 2);
            writer.writeInt(positioningSystem, 4);
            writer.writeInt(timeOffset, 6);
            writer.writeBool(useFreqRange2);
            writer.writeBool(pttLock);
            writer.writeBool(leadingSyncBitEn);
            writer.writeBool(pairingAtPowerOn);
            writer.writeInt(screenTimeout, 5);
            writer.writeInt(nextVfoX, 2);
            writer.writeBool(imperialUnit);
            writer.writeInt((nextA >> 4) & 0x0F, 4);
            writer.writeInt((nextB >> 4) & 0x0F, 4);
            writer.writeInt(wxMode, 2);
            writer.writeInt(noaaCh, 4);
            writer.writeInt(vfolTxPowerX, 2);
            writer.writeInt(vfo2TxPowerX, 2);
            writer.writeBool(disDigitalMute);
            writer.writeBool(signalingEccEn);
            writer.writeBool(chDataLock);
            writer.skipBits(3);

            byte[] first12 = writer.toByteArray();
            byte[] result = rawSettings.clone();
            System.arraycopy(first12, 0, result, 0, Math.min(12, first12.length));
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to modify settings bytes", e);
            return null;
        }
    }

    private static int parseCurrentChannelIdFromHtStatus(byte[] payload) {
        int currChIdLower = (payload[1] >> 4) & 0x0F;
        int currChannelIdUpper = 0;
        if (payload.length >= 4) {
            currChannelIdUpper = (payload[3] & 0x3C) >> 2;
        }
        return (currChannelIdUpper << 4) + currChIdLower;
    }

    /**
     * HT status channel type bits:
     * 0 = off/single, 1 = A, 2 = B (based on HTCommander reverse-engineering).
     */
    private static int parseChannelTypeFromHtStatus(byte[] payload) {
        if (payload == null || payload.length < 1) {
            return -1;
        }
        return (payload[0] & 0x0C) >> 2;
    }

    private static ChannelSummary parseChannelSummary(byte[] payload, int fallbackChannelId) {
        // Keep the UI slot order deterministic: slot index drives channel identity.
        int channelId = Math.max(0, fallbackChannelId);
        int txFreqHz = ((payload[1] & 0x3F) << 24)
                | ((payload[2] & 0xFF) << 16)
                | ((payload[3] & 0xFF) << 8)
                | (payload[4] & 0xFF);
        int rxFreqHz = ((payload[5] & 0x3F) << 24)
                | ((payload[6] & 0xFF) << 16)
                | ((payload[7] & 0xFF) << 8)
                | (payload[8] & 0xFF);
        boolean scanEnabled = (payload[13] & 0x80) != 0;
        boolean muted = (payload[14] & 0x10) != 0;
        int txToneRaw = ((payload[9] & 0xFF) << 8) | (payload[10] & 0xFF);
        int rxToneRaw = ((payload[11] & 0xFF) << 8) | (payload[12] & 0xFF);
        String name = decodeChannelName(payload);
        return new ChannelSummary(
                channelId,
                name,
                rxFreqHz <= 0 ? 0.0 : (rxFreqHz / 1_000_000.0),
                txFreqHz <= 0 ? 0.0 : (txFreqHz / 1_000_000.0),
                decodeTone(txToneRaw),
                decodeTone(rxToneRaw),
                scanEnabled,
                muted
        );
    }

    private static Object decodeTone(int raw) {
        if (raw <= 0) {
            return null;
        }
        // CTCSS values are typically encoded as frequency * 100 (e.g. 100.0 -> 10000).
        // Small values are treated as DCS integer codes.
        if (raw >= 6700) {
            return raw / 100.0;
        }
        return raw;
    }

    /**
     * Radios/firmware can report channel ids as 0-based or 1-based.
     * Normalize to 0-based grid index [0, count-1], or -1 if invalid.
     */
    private static int normalizeToGridChannel(int raw, int count) {
        if (raw >= 0 && raw < count) {
            return raw; // already 0-based
        }
        if (raw >= 1 && raw <= count) {
            return raw - 1; // 1-based -> 0-based
        }
        return -1;
    }

    /**
     * Digital channel field uses 1..N in practice; normalize explicitly.
     */
    private static int normalizeDigitalChannel(int raw, int count) {
        if (raw >= 1 && raw <= count) {
            return raw - 1;
        }
        if (raw >= 0 && raw < count) {
            return raw;
        }
        return -1;
    }

    private static String decodeChannelName(byte[] payload) {
        int nameStart = 15;
        if (payload.length <= nameStart) {
            return "";
        }
        int nameEnd = Math.min(payload.length, nameStart + 10);
        String raw = new String(Arrays.copyOfRange(payload, nameStart, nameEnd),
                StandardCharsets.UTF_8);
        int nullPos = raw.indexOf('\0');
        if (nullPos >= 0) {
            raw = raw.substring(0, nullPos);
        }
        return raw.trim();
    }

    private static class SettingsState {
        int channelA;
        int channelB;
        int doubleChannel;
        int autoShareLocCh;
        int vfoX;

        static SettingsState parse(byte[] rawSettings) {
            if (rawSettings == null || rawSettings.length < 12) {
                return null;
            }
            try {
                SettingsState state = new SettingsState();
                BitReader reader = new BitReader(rawSettings);
                int channelALower = reader.readInt(4);
                int channelBLower = reader.readInt(4);
                reader.readBool();
                reader.readInt(1);
                state.doubleChannel = reader.readInt(2);
                reader.readInt(4);
                reader.readBool();
                reader.readBool();
                reader.readBool();
                reader.readBool();
                reader.readInt(3);
                reader.readInt(4);
                reader.readInt(5);
                reader.readInt(2);
                reader.readInt(3);
                reader.readBool();
                reader.readBool();
                reader.readBool();
                reader.readInt(3);
                state.autoShareLocCh = reader.readInt(5);
                reader.readInt(2);
                reader.readInt(4);
                reader.readInt(6);
                reader.readBool();
                reader.readBool();
                reader.readBool();
                reader.readBool();
                reader.readInt(5);
                state.vfoX = reader.readInt(2);
                reader.readBool();
                int channelAUpper = reader.readInt(4);
                int channelBUpper = reader.readInt(4);
                state.channelA = (channelAUpper << 4) | channelALower;
                state.channelB = (channelBUpper << 4) | channelBLower;
                return state;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static byte[] createRadioCommand(int commandGroup, int command, byte[] body) {
        int messageSize = 4 + body.length;
        BitWriter writer = new BitWriter(messageSize);
        writer.writeInt(commandGroup, 16);
        writer.writeBool(false);
        writer.writeInt(command, 15);
        writer.writeBytes(body);
        byte[] messageBytes = writer.toByteArray();

        int payloadLen = messageBytes.length - 4;
        byte[] frame = new byte[4 + messageBytes.length];
        frame[0] = (byte) 0xFF;
        frame[1] = 0x01;
        frame[2] = 0x00;
        frame[3] = (byte) payloadLen;
        System.arraycopy(messageBytes, 0, frame, 4, messageBytes.length);
        return frame;
    }

    private static class ProtocolMessage {
        final int commandGroup;
        final boolean isReply;
        final int command;
        final byte[] bodyBytes;

        ProtocolMessage(int commandGroup, boolean isReply, int command, byte[] bodyBytes) {
            this.commandGroup = commandGroup;
            this.isReply = isReply;
            this.command = command;
            this.bodyBytes = bodyBytes;
        }

        static ProtocolMessage parse(byte[] bytes) {
            BitReader reader = new BitReader(bytes);
            int group = reader.readInt(16);
            boolean reply = reader.readBool();
            int cmd = reader.readInt(15);
            byte[] body = reader.readBytes(reader.remainingBits() / 8);
            return new ProtocolMessage(group, reply, cmd, body);
        }
    }

    private static class RadioChannel {
        final int channelId;
        final int txMod;
        final double txFreq;
        final int rxMod;
        final double rxFreq;
        final Object txSubAudio;
        final Object rxSubAudio;
        final boolean scan;
        final boolean txAtMaxPower;
        final boolean talkAround;
        final int bandwidth;
        final boolean preDeEmphBypass;
        final boolean sign;
        final boolean txAtMedPower;
        final boolean txDisable;
        final boolean fixedFreq;
        final boolean fixedBandwidth;
        final boolean fixedTxPower;
        final boolean mute;
        final String name;

        RadioChannel(int channelId, int txMod, double txFreq, int rxMod, double rxFreq,
                     Object txSubAudio, Object rxSubAudio, boolean scan, boolean txAtMaxPower,
                     boolean talkAround, int bandwidth, boolean preDeEmphBypass, boolean sign,
                     boolean txAtMedPower, boolean txDisable, boolean fixedFreq,
                     boolean fixedBandwidth, boolean fixedTxPower, boolean mute, String name) {
            this.channelId = channelId;
            this.txMod = txMod;
            this.txFreq = txFreq;
            this.rxMod = rxMod;
            this.rxFreq = rxFreq;
            this.txSubAudio = txSubAudio;
            this.rxSubAudio = rxSubAudio;
            this.scan = scan;
            this.txAtMaxPower = txAtMaxPower;
            this.talkAround = talkAround;
            this.bandwidth = bandwidth;
            this.preDeEmphBypass = preDeEmphBypass;
            this.sign = sign;
            this.txAtMedPower = txAtMedPower;
            this.txDisable = txDisable;
            this.fixedFreq = fixedFreq;
            this.fixedBandwidth = fixedBandwidth;
            this.fixedTxPower = fixedTxPower;
            this.mute = mute;
            this.name = name;
        }

        byte[] toBytes() {
            BitWriter writer = new BitWriter(25);
            writer.writeInt(channelId, 8);
            writer.writeInt(txMod, 2);
            writer.writeInt((int) (txFreq * 1e6), 30);
            writer.writeInt(rxMod, 2);
            writer.writeInt((int) (rxFreq * 1e6), 30);
            writer.writeInt(encodeTone(txSubAudio), 16);
            writer.writeInt(encodeTone(rxSubAudio), 16);
            writer.writeBool(scan);
            writer.writeBool(txAtMaxPower);
            writer.writeBool(talkAround);
            writer.writeInt(bandwidth, 1);
            writer.writeBool(preDeEmphBypass);
            writer.writeBool(sign);
            writer.writeBool(txAtMedPower);
            writer.writeBool(txDisable);
            writer.writeBool(fixedFreq);
            writer.writeBool(fixedBandwidth);
            writer.writeBool(fixedTxPower);
            writer.writeBool(mute);
            writer.writeInt(0, 4);

            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            byte[] padded = Arrays.copyOf(nameBytes, 10);
            writer.writeBytes(padded);
            return writer.toByteArray();
        }

        private int encodeTone(Object tone) {
            if (tone == null) {
                return 0;
            }
            if (tone instanceof Double) {
                return (int) (((Double) tone) * 100.0);
            }
            if (tone instanceof Integer) {
                return (Integer) tone;
            }
            return 0;
        }
    }

    private static class BitWriter {
        private final byte[] buffer;
        private int bitPos = 0;

        BitWriter(int size) {
            this.buffer = new byte[size];
        }

        void writeInt(int value, int bitLength) {
            for (int i = bitLength - 1; i >= 0; i--) {
                writeBool(((value >> i) & 1) == 1);
            }
        }

        void writeBool(boolean value) {
            int bytePos = bitPos / 8;
            int bitInByte = 7 - (bitPos % 8);
            if (value) {
                buffer[bytePos] = (byte) (buffer[bytePos] | (1 << bitInByte));
            }
            bitPos++;
        }

        void writeBytes(byte[] data) {
            for (byte b : data) {
                writeInt(b & 0xFF, 8);
            }
        }

        void skipBits(int bits) {
            bitPos += bits;
            if (bitPos > buffer.length * 8) {
                bitPos = buffer.length * 8;
            }
        }

        byte[] toByteArray() {
            int bytes = bitPos / 8 + (bitPos % 8 == 0 ? 0 : 1);
            return Arrays.copyOf(buffer, bytes);
        }
    }

    private static class BitReader {
        private final byte[] data;
        private int bitPos = 0;

        BitReader(byte[] data) {
            this.data = data;
        }

        int remainingBits() {
            return (data.length * 8) - bitPos;
        }

        int readInt(int bitLength) {
            int out = 0;
            for (int i = 0; i < bitLength; i++) {
                int byteIndex = bitPos / 8;
                int bitIndex = 7 - (bitPos % 8);
                int bit = ((data[byteIndex] >> bitIndex) & 1);
                out = (out << 1) | bit;
                bitPos++;
            }
            return out;
        }

        boolean readBool() {
            return readInt(1) == 1;
        }

        void skipBits(int bits) {
            bitPos = Math.min(data.length * 8, bitPos + bits);
        }

        byte[] readBytes(int byteCount) {
            byte[] out = new byte[byteCount];
            for (int i = 0; i < byteCount; i++) {
                out[i] = (byte) readInt(8);
            }
            return out;
        }
    }
}
