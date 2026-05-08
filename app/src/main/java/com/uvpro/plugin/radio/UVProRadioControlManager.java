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
    private static final int CMD_WRITE_RF_CH = 14;
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

    public static class ProgramResult {
        public final boolean success;
        public final String message;

        public ProgramResult(boolean success, String message) {
            this.success = success;
            this.message = message;
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
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();

    private PendingRequest pending;
    private RepeaterSpec selectedRepeater;
    private SelectionListener selectionListener;

    public UVProRadioControlManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    public void start() {
        btManager.addRawDataListener(this);
    }

    public void stop() {
        btManager.removeRawDataListener(this);
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

            int newChannelALower = channelALower;
            int newChannelAUpper = channelAUpper;
            int newChannelBLower = channelBLower;
            int newChannelBUpper = channelBUpper;

            if (dualWatchMode == 2) {
                newChannelBLower = channelId & 0x0F;
                newChannelBUpper = (channelId >> 4) & 0x0F;
            } else {
                newChannelALower = channelId & 0x0F;
                newChannelAUpper = (channelId >> 4) & 0x0F;
            }

            BitWriter writer = new BitWriter(12);
            writer.writeInt(newChannelALower, 4);
            writer.writeInt(newChannelBLower, 4);
            writer.writeBool(scan);
            writer.writeInt(aghfpCallMode, 1);
            writer.writeInt(doubleChannel, 2);
            writer.writeInt(squelchLevel, 4);
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
            writer.writeInt(autoShareLocCh, 5);
            writer.writeInt(hmSpeaker, 2);
            writer.writeInt(positioningSystem, 4);
            writer.writeInt(timeOffset, 6);
            writer.writeBool(useFreqRange2);
            writer.writeBool(pttLock);
            writer.writeBool(leadingSyncBitEn);
            writer.writeBool(pairingAtPowerOn);
            writer.writeInt(screenTimeout, 5);
            writer.writeInt(vfoX, 2);
            writer.writeBool(imperialUnit);
            writer.writeInt(newChannelAUpper, 4);
            writer.writeInt(newChannelBUpper, 4);
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
