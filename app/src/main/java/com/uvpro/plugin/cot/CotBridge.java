package com.uvpro.plugin.cot;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.util.Log;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.icons.UserIcon;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsLogger;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;

import com.uvpro.plugin.BuildConfig;
import com.uvpro.plugin.ax25.AprsSymbolMapper;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.beacon.SmartBeacon;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.UVProPacket;
import com.uvpro.plugin.protocol.PacketFragmenter;
import com.uvpro.plugin.ui.SettingsFragment;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Bridges between ATAK CoT events and the radio link.
 *
 * Inbound (radio → ATAK):
 *   - Receives decoded data from PacketRouter
 *   - Builds CotEvent objects using CotBuilder
 *   - Dispatches them into ATAK's CoT processing pipeline
 *
 * Outbound (ATAK → radio):
 *   - Listens for local CoT events from ATAK
 *   - Compresses and fragments if needed
 *   - Sends via radio link
 */
public class CotBridge {

    private static final String TAG = "UVPro.CotBridge";

    /** Same action as ATAK radial menu {@code actions/showdetails.xml}. */
    public static final String ACTION_COT_MARKER_DETAILS =
            "com.atakmap.android.cotdetails.COTINFO";

    /** Set on map items created from inbound APRS position reports. */
    public static final String META_UVPRO_APRS = "uvpro_aprs";

    /** Multi-line APRS packet metadata for {@link com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver}. */
    public static final String META_UVPRO_APRS_DETAILS = "uvpro_aprs_details";
    private static final long STALE_GRACE_MS = 30_000L;
    private static final long MIN_CONTACT_STALE_MS = 60_000L;
    /** Inbound APRS / radio peers: ATAK uses CoT stale as marker TTL; keep ≥ 2h for sparse beacons. */
    private static final long MIN_INBOUND_RADIO_STALE_MS = 2 * 60 * 60_000L;

    private final Context pluginContext;
    private final MapView mapView;

    /** Set from UI thread once; BT read thread often cannot resolve {@link MapView#getDeviceUid()}. */
    private volatile String cachedLocalDeviceUidForGeoChat;
    private BtConnectionManager btManager;
    private String localCallsign = "OPENRL";
    private EncryptionManager encryptionManager;
    private CommsMapComponent.PreSendProcessor preSendProcessor;
    private BroadcastReceiver localCotBroadcastReceiver;

    /** Catches outbound GeoChat: ATAK sends via CotMapComponent external dispatcher, not PreSend. */
    private CommsLogger outboundCommsLogger;

    /** Whether to relay all outgoing SA to radio (can flood the channel) */
    private boolean relayOutgoingSa = false;

    /**
     * UIDs that the plugin considers radio-transport contacts.
     * Populated when the plugin creates/registers contacts from radio packets.
     */
    private final Set<String> btechContactUids = ConcurrentHashMap.newKeySet();

    /**
     * Map plugin-created display identifiers to contact UIDs.
     * Key is typically the normalized callsign (upper) or a known chat-room label.
     */
    private final Map<String, String> btechIdToUid = new ConcurrentHashMap<>();

    /** Minimum interval between outgoing SA relays (ms) */
    private static final long SA_RELAY_INTERVAL_MS = 30_000;
    private long lastSaRelay = 0;

    /** Per-UID throttle map for SA Relay to prevent channel flooding */
    private final Map<String, Long> saRelayLastSentByUid = new ConcurrentHashMap<>();
    /** Short de-dupe window so PreSend + COT_PLACED do not double-transmit the same event. */
    private final Map<String, Long> recentLocalRelayKeys = new ConcurrentHashMap<>();
    private static final long LOCAL_RELAY_DEDUPE_MS = 1500L;

    /**
     * Inbound network CoT types eligible for SA Relay (network → radio).
     * Matches friendly SA ({@code a-*-G…}), points/markers ({@code b-m-p…}), routes ({@code b-m-r…}).
     */
    private static final java.util.regex.Pattern SA_RELAY_TYPE_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(a-[a-z]-G|b-m-p|b-m-r)");
    private static final String ALL_CHAT_ROOMS = "All Chat Rooms";

    /**
     * Read SA Relay preference from plugin SharedPreferences.
     * Called on the radio-receive thread; SharedPreferences reads are thread-safe.
     */
    private boolean isSaRelayEnabled() {
        try {
            return com.uvpro.plugin.ui.SettingsFragment
                    .isSaRelayEnabled(pluginContext);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRfToTakUplinkEnabled() {
        try {
            return isSaRelayEnabled() && com.uvpro.plugin.ui.SettingsFragment
                    .isRfToTakUplinkEnabled(pluginContext);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Injected inbound GeoChat (and similar) is re-processed by core comms; PreSendProcessor
     * then sees the same b-t-f with toUIDs pointing at a BTECH contact and would re-transmit
     * over RF (echo loop, duplicate fragments, unknown receipt UIDs).
     */
    private static final long INBOUND_INJECT_NO_RELAY_MS = 10_000L;
    private final Map<String, Long> inboundInjectNoRelayUntil = new ConcurrentHashMap<>();

    /** Set after {@link ChatBridge} construction; used to send compact TYPE_CHAT with wire ACK ids. */
    private volatile ChatBridge chatBridge;

    private void markInboundInjectSkipOutboundRelay(String cotUid) {
        if (cotUid == null || cotUid.isEmpty()) return;
        inboundInjectNoRelayUntil.put(cotUid,
                System.currentTimeMillis() + INBOUND_INJECT_NO_RELAY_MS);
    }

    private boolean shouldSkipOutboundRelayWasInboundInject(String cotUid) {
        if (cotUid == null) return false;
        Long until = inboundInjectNoRelayUntil.get(cotUid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            inboundInjectNoRelayUntil.remove(cotUid);
            return false;
        }
        return true;
    }

    public CotBridge(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
    }

    public void setBtManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    public void setLocalCallsign(String callsign) {
        this.localCallsign = callsign;
    }

    /**
     * Snapshot local ATAK device/self-marker UID on the UI thread for GeoChat {@code chatgrp} uid1
     * when injecting inbound chat from Bluetooth RX (background thread).
     */
    public void refreshCachedLocalDeviceUidForGeoChat() {
        String u = tryResolveAtakSelfUidForChatGrp(mapView);
        if (u != null) {
            cachedLocalDeviceUidForGeoChat = u;
            Log.d(TAG, "Cached local ATAK UID for inbound GeoChat DMs: " + u);
        }
    }

    public void setRelayOutgoingSa(boolean relay) {
        this.relayOutgoingSa = relay;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    public void setChatBridge(ChatBridge chatBridge) {
        this.chatBridge = chatBridge;
    }

    /**
     * Register a contact UID as a UV-PRO radio endpoint.
     * This is used to route ATAK "send to contact" actions to the radio link
     * without globally relaying all CoT.
     */
    public void registerBtechContactUid(String uid) {
        if (uid == null) return;
        btechContactUids.add(uid);
    }

    /**
     * Register a plugin-created BTECH contact identifier (e.g. callsign) → UID mapping.
     * Used for routing GeoChat messages where ATAK does not provide explicit toUIDs.
     */
    public void registerBtechContactId(String id, String uid) {
        if (id == null || uid == null) return;
        String key = id.trim().toUpperCase();
        if (key.isEmpty()) return;
        btechIdToUid.put(key, uid);
        registerBtechContactUid(uid);
    }

    public boolean isBtechContactUid(String uid) {
        return uid != null && btechContactUids.contains(uid);
    }

    private static final String ANDROID_UID_PREFIX = "ANDROID-";

    /**
     * ATAK GeoChat/direct destinations sometimes use the literal contact UID label
     * (e.g. ANDROID-VETTE1); registration keys are typically the bare callsign
     * (VETTE1). Normalizes for routing-map lookup.
     */
    private static String normalizeBtechRoutingId(String id) {
        if (id == null) return "";
        String key = id.trim().toUpperCase();
        if (key.startsWith(ANDROID_UID_PREFIX)) {
            key = key.substring(ANDROID_UID_PREFIX.length());
        }
        return key;
    }

    /**
     * Resolve a chat destination label/callsign to a BTECH contact UID, if known.
     */
    public String resolveBtechUidForId(String id) {
        if (id == null) return null;
        String key = id.trim().toUpperCase();
        if (key.isEmpty()) return null;
        String mapped = btechIdToUid.get(key);
        if (mapped != null) return mapped;
        String stripped = normalizeBtechRoutingId(id);
        if (!stripped.isEmpty() && !stripped.equals(key)) {
            mapped = btechIdToUid.get(stripped);
            if (mapped != null) return mapped;
        }
        return null;
    }

    /**
     * True if an outbound GeoChat/send intent targets a plugin-registered radio
     * contact, using UID, chat-room label, or ATAK ANDROID-* display identifiers.
     */
    public boolean isBtechOutboundChatDestination(String uid, String chatroom) {
        if (uid != null) {
            String u = uid.trim();
            if (!u.isEmpty() && isBtechContactUid(u)) return true;
            String resolvedUid = resolveBtechUidForId(u);
            if (resolvedUid != null && isBtechContactUid(resolvedUid)) return true;
        }
        if (chatroom != null && !chatroom.isEmpty()) {
            if ("ALL CHAT ROOMS".equalsIgnoreCase(chatroom.trim())) return false;
            String resolvedRm = resolveBtechUidForId(chatroom);
            if (resolvedRm != null && isBtechContactUid(resolvedRm)) return true;
        }
        return false;
    }

    /**
     * Decide whether an ATAK outbound event (from broadcast/intent) should be
     * relayed to the radio based on its destination UIDs.
     *
     * Many ATAK broadcasts include a `toUIDs` extra. If that list intersects
     * with our plugin-created contact UIDs, we treat it as a radio-targeted send.
     */
    public boolean shouldRelayToRadio(Intent intent) {
        if (intent == null) return false;
        try {
            java.util.ArrayList<String> toUIDs =
                    intent.getStringArrayListExtra("toUIDs");
            if (toUIDs == null || toUIDs.isEmpty()) return false;
            for (String uid : toUIDs) {
                if (uid != null && btechContactUids.contains(uid)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Decide whether an outbound GeoChat CoT event should be relayed to radio.
     *
     * ATAK GeoChat CoT varies by release: {@code __chat} vs {@code chat}, and
     * destination may appear under {@code chatgrp}, {@code chatroom}, or only in
     * the serialized XML. We combine structured parsing with a containment check
     * against registered radio-contact UIDs.
     */
    public boolean shouldRelayGeoChatToRadio(CotEvent event) {
        if (event == null) return false;
        try {
            if (!"b-t-f".equals(event.getType())) return false;
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) return false;

            com.atakmap.coremap.cot.event.CotDetail chat =
                    detail.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = detail.getFirstChildByName(0, "chat");
            }
            if (chat != null && geoChatDetailTargetsBtechContact(chat, detail)) {
                return true;
            }

            // Some ATAK layouts omit renameable wrappers; probe full serialization.
            if (geoChatXmlReferencesRegisteredBtechContact(event)) {
                Log.d(TAG, "GeoChat relay: matched BTECH UID via CoT substring probe");
                return true;
            }

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean geoChatDetailTargetsBtechContact(
            com.atakmap.coremap.cot.event.CotDetail chat,
            com.atakmap.coremap.cot.event.CotDetail detail) {

        com.atakmap.coremap.cot.event.CotDetail chatgrp =
                chat.getFirstChildByName(0, "chatgrp");
        if (chatgrp != null) {
            String uid0 = chatgrp.getAttribute("uid0");
            String uid1 = chatgrp.getAttribute("uid1");
            if (isBtechContactUid(uid0) || isBtechContactUid(uid1)) {
                return true;
            }
        }

        for (String attr : new String[] {"chatroom", "id", "destination", "recipient"}) {
            String chatRoom = chat.getAttribute(attr);
            String uidFromRoom = resolveBtechUidForId(chatRoom);
            if (isBtechContactUid(uidFromRoom)) return true;
        }

        com.atakmap.coremap.cot.event.CotDetail remarks =
                detail.getFirstChildByName(0, "remarks");
        if (remarks != null) {
            String to = remarks.getAttribute("to");
            String uidFromTo = resolveBtechUidForId(to);
            if (isBtechContactUid(uidFromTo)) return true;
        }
        return false;
    }

    /**
     * Last-resort matcher for outbound b-t-f when structured {@code __chat} is absent
     * or uses nonstandard tags (different ATAK revisions).
     */
    private boolean geoChatXmlReferencesRegisteredBtechContact(CotEvent event) {
        try {
            if (btechContactUids.isEmpty()) return false;
            String s = event.toString();
            if (s == null || s.length() > 524288) return false;
            for (String uid : btechContactUids) {
                if (uid != null && uid.length() > 8 && s.indexOf(uid) >= 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Backwards-compatible name for chat routing decisions.
     */
    public boolean shouldRelayChatCotToRadio(CotEvent event) {
        return shouldRelayGeoChatToRadio(event);
    }

    /**
     * Inject a position CoT event into ATAK from radio GPS data.
     *
     * @param senderTeamFromPeer ATAK {@code locationTeam} from the transmitting node
     *                           (embedded in GPS packet). If null or empty (legacy/APRS path),
     *                           falls back to {@code "Cyan"} so we do not apply the
     *                           <em>receiver's</em> team tint to peers.
     */
    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, null, null, null);
    }

    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer, String cotTypeOverride) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, cotTypeOverride, null, null, null);
    }

    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer,
                                  Character aprsSymbolTable,
                                  Character aprsSymbolCode) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, aprsSymbolTable, aprsSymbolCode, null);
    }

    /**
     * Injects position CoT with optional remarks (e.g. APRS telemetry summary at last fix).
     */
    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer,
                                  Character aprsSymbolTable,
                                  Character aprsSymbolCode,
                                  String remarksInner) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, aprsSymbolTable, aprsSymbolCode, remarksInner);
    }

    private void injectPositionCot(String callsign, double lat, double lon,
                                   double alt, double speed, double course,
                                   String senderTeamFromPeer, String cotTypeOverride,
                                   Character aprsSymbolTable, Character aprsSymbolCode,
                                   String remarksInner) {
        try {
            String teamForCot = senderTeamFromPeer != null && !senderTeamFromPeer.trim().isEmpty()
                    ? senderTeamFromPeer.trim()
                    : "Cyan";

            CotEvent event = CotBuilder.buildPositionCot(
                    callsign, lat, lon, alt, speed, course, teamForCot,
                    resolveInboundContactStaleMs(),
                    cotTypeOverride,
                    aprsSymbolTable,
                    aprsSymbolCode,
                    remarksInner);

            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting position CoT for " + callsign + " team=" + teamForCot);
                markInboundInjectSkipOutboundRelay(event.getUID());
                dispatchCotEvent(event);
                maybeRelayInboundRadioCotToTak(event);
            } else {
                Log.w(TAG, "Invalid position CoT for " + callsign);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting position CoT", e);
        }
    }

    /**
     * Receiver-side stale policy for inbound radio contacts.
     * ATAK marks contacts stale based on the CoT stale timestamp we dispatch.
     */
    private long resolveInboundContactStaleMs() {
        try {
            Context prefsCtx = mapView != null && mapView.getContext() != null
                    ? mapView.getContext()
                    : pluginContext;
            if (prefsCtx == null) {
                return MIN_INBOUND_RADIO_STALE_MS;
            }

            long staleMs;
            if (SmartBeacon.isEnabled(prefsCtx)) {
                int slowRateSec = Math.max(1, SmartBeacon.getSlowRate(prefsCtx));
                int fastRateSec = Math.max(1, SmartBeacon.getFastRate(prefsCtx));
                int minTurnTimeSec = Math.max(1, SmartBeacon.getMinTurnTime(prefsCtx));
                int expectedMaxGapSec = Math.max(slowRateSec, fastRateSec + minTurnTimeSec);
                staleMs = expectedMaxGapSec * 1000L;
            } else {
                int fixedSec = Math.max(1, SettingsFragment.getBeaconIntervalSec(pluginContext));
                staleMs = fixedSec * 1000L;
            }
            long fromBeacon = Math.max(MIN_CONTACT_STALE_MS, staleMs + STALE_GRACE_MS);
            return Math.max(MIN_INBOUND_RADIO_STALE_MS, fromBeacon);
        } catch (Exception ignored) {
            return MIN_INBOUND_RADIO_STALE_MS;
        }
    }

    /**
     * Inject GeoChat delivered ({@code b-t-f-d}) or read ({@code b-t-f-r}) receipt for a sent line.
     */
    public void injectGeoChatReceipt(String referencedOriginalMessageLineUid,
                                     boolean readNotDelivered) {
        try {
            CotEvent event = CotBuilder.buildGeoChatReceiptCot(
                    referencedOriginalMessageLineUid,
                    readNotDelivered,
                    cachedLocalDeviceUidForGeoChat,
                    localCallsign);
            if (event == null || !event.isValid()) {
                return;
            }
            markInboundInjectSkipOutboundRelay(event.getUID());
            dispatchCotEvent(event);
            broadcastReceiptCotPlaced(event);
            Log.d(TAG, "Injected GeoChat receipt type=" + event.getType()
                    + " cotUID=" + event.getUID()
                    + " for line=" + referencedOriginalMessageLineUid);
        } catch (Exception e) {
            Log.e(TAG, "Error injecting GeoChat receipt", e);
        }
    }

    /**
     * Some ATAK GeoChat paths hook {@code COT_PLACED} rather than the internal dispatcher alone;
     * receipts still skip RF relay via PreSend guards on {@code b-t-f-d}/{@code b-t-f-r}.
     */
    private void broadcastReceiptCotPlaced(CotEvent event) {
        if (event == null) {
            return;
        }
        try {
            Intent intent = new Intent("com.atakmap.android.maps.COT_PLACED");
            intent.putExtra("xml", event.toString());
            AtakBroadcast.getInstance().sendBroadcast(intent);
            Log.d(TAG, "Broadcast COT_PLACED for GeoChat receipt uid=" + event.getUID());
        } catch (Exception e) {
            Log.w(TAG, "COT_PLACED broadcast for GeoChat receipt failed", e);
        }
    }

    /**
     * Inject a compressed CoT XML received from another UV-PRO node.
     */
    public void injectCompressedCot(byte[] compressed) {
        try {
            String xml = CotBuilder.decompressCot(compressed);
            if (xml == null || xml.isEmpty()) {
                Log.w(TAG, "Failed to decompress CoT");
                return;
            }

            CotEvent event = CotEvent.parse(xml);
            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting decompressed CoT: type=" + event.getType()
                        + " uid=" + event.getUID());
                // Mark ALL injected CoT to skip outbound RF relay — prevents the
                // PreSendProcessor from echoing received items back over the air.
                markInboundInjectSkipOutboundRelay(event.getUID());
                dispatchCotEvent(event);
                maybeRelayInboundRadioCotToTak(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting compressed CoT", e);
        }
    }

    /**
     * Inject a chat CoT event into ATAK.
     */
    /**
     * @param radioPacketMessageId UV-PRO wire id ({@literal >} 0 distinguishes duplicate ATAK merges); 0 = unknown
     */
    public void injectChatCot(String senderCallsign, String message,
                              String chatRoom, int radioPacketMessageId) {
        try {
            String trimmed = senderCallsign != null ? senderCallsign.trim() : "";
            // Align with GPS-registered contacts: AX.25 truncates sender (e.g. JUNIOR → JNR).
            String canonicalUid = resolveBtechUidForId(trimmed);
            if (canonicalUid == null && !trimmed.isEmpty()) {
                String key = normalizeBtechRoutingId(trimmed);
                if (!key.isEmpty()) {
                    canonicalUid = ANDROID_UID_PREFIX + key;
                }
            }
            if (canonicalUid == null || canonicalUid.isEmpty()) {
                Log.w(TAG, "injectChatCot: no UID for sender " + trimmed);
                return;
            }
            String displayCallsign = canonicalUid.startsWith(ANDROID_UID_PREFIX)
                    ? canonicalUid.substring(ANDROID_UID_PREFIX.length())
                    : trimmed.toUpperCase();
            // GeoChat dedupes/threads by messageId (Cot UID). If we use only wire mid (1,2,3...),
            // restarting ATAK (or receiver) with existing chat history causes collisions and the
            // UI "updates" an old row instead of inserting the new message. Make the UID globally
            // unique while still embedding the wire mid for debugging.
            long uniq;
            if (radioPacketMessageId != 0) {
                long mid = ((long) radioPacketMessageId) & 0xffffffffL;
                long t = System.currentTimeMillis() & 0xffffffffL;
                uniq = (mid << 32) | t;
            } else {
                uniq = System.nanoTime();
            }
            // Peer ANDROID-* DM: GeoChat expects chatgrp uid0=peer, uid1=local self. Radio RX runs on
            // BT thread; MapView.getDeviceUid() is often NULL there — omitting uid1 caused
            // GeoChat.ANDROID-VETTE.ANDROID-VETTE and broken UI / ACK path.
            String chatGrpUid1ForDm = null;
            if (chatRoom != null && chatRoom.startsWith("ANDROID-")) {
                chatGrpUid1ForDm = cachedLocalDeviceUidForGeoChat;
                if (chatGrpUid1ForDm == null) {
                    chatGrpUid1ForDm = resolveLocalAtakUidForChatGrp(canonicalUid, chatRoom);
                }
            }

            CotEvent event = CotBuilder.buildChatCot(
                    canonicalUid, displayCallsign, message, chatRoom, uniq,
                    chatGrpUid1ForDm);

            if (event != null && event.isValid()) {
                if (chatGrpUid1ForDm != null && chatRoom != null
                        && chatRoom.startsWith(ANDROID_UID_PREFIX)) {
                    Log.d(TAG, "GeoChat DM __chat id(local)=" + chatGrpUid1ForDm
                            + " chatroom(callsign)=" + displayCallsign
                            + " peerTHREAD=" + chatRoom + " sender=" + canonicalUid);
                }
                Log.d(TAG, "Injecting chat CoT from " + displayCallsign
                        + " (uid=" + canonicalUid + " midpkt=" + radioPacketMessageId + ")");
                markInboundInjectSkipOutboundRelay(event.getUID());
                dispatchCotEvent(event);
                maybeRelayInboundRadioCotToTak(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting chat CoT", e);
        }
    }

    /**
     * Send a CoT event out over the radio link.
     * The CoT XML is gzipped and sent as an UV-PRO packet.
     */
    /** Max compressed CoT size to send over RF. Larger items flood the channel. */
    private static final int MAX_COT_COMPRESSED_BYTES = 4096;

    public void sendCotOverRadio(CotEvent event) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Not connected to radio — cannot send CoT");
            return;
        }

        try {
            String xml = event.toString();
            byte[] compressed = CotBuilder.compressCot(xml);
            if (compressed == null) {
                Log.e(TAG, "Failed to compress CoT for radio");
                return;
            }

            if (compressed.length > MAX_COT_COMPRESSED_BYTES) {
                Log.w(TAG, "CoT too large for RF (" + compressed.length + " bytes compressed"
                        + ", type=" + event.getType() + " uid=" + event.getUID()
                        + ") — skipping to avoid blocking channel");
                return;
            }

            Log.d(TAG, "Sending CoT over radio: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " xmlBytes=" + xml.length()
                    + " compressedBytes=" + compressed.length);

            // Fragment if needed
            List<UVProPacket> packets = PacketFragmenter.fragment(
                    UVProPacket.TYPE_COT, compressed);

            for (UVProPacket packet : packets) {
                byte[] packetBytes = packet.encode();
                // Encrypt entire packet bytes if enabled
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        Log.e(TAG, "Encryption failed — aborting CoT send");
                        return;
                    }
                }
                Ax25Frame frame = Ax25Frame.createUVProFrame(
                        localCallsign, 0, packetBytes);
                byte[] ax25 = frame.encode();
                btManager.sendKissFrame(ax25);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending CoT over radio", e);
        }
    }

    /**
     * Team string from ATAK Settings (sender side) for outbound GPS TLV extension.
     */
    private String resolveLocalAtakTeamForOutbound() {
        try {
            String t = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (t != null && !t.trim().isEmpty()) {
                return t.trim();
            }
        } catch (Exception ignored) {
        }
        try {
            return com.uvpro.plugin.ui.SettingsFragment.getAtakTeamColor(pluginContext);
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    /**
     * Send local GPS position over radio as compact GPS packet.
     */
    public void sendPositionOverRadio(double lat, double lon, double alt,
                                      float speed, float course, int battery) {
        if (btManager == null || !btManager.isConnected()) return;

        try {
            UVProPacket packet = UVProPacket.createGpsPacket(
                    com.uvpro.plugin.util.CallsignUtil.toRadioCallsign(localCallsign),
                    localCallsign, lat, lon, (float) alt,
                    speed, course, battery,
                    resolveLocalAtakTeamForOutbound());

            byte[] packetBytes = packet.encode();

            // Encrypt entire packet bytes if enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encryption failed — aborting GPS send");
                    return;
                }
            }

            Ax25Frame frame = Ax25Frame.createUVProFrame(
                    localCallsign, 0, packetBytes);
            byte[] ax25 = frame.encode();

            Log.d(TAG, "Sending GPS beacon: " + ax25.length + " bytes");
            btManager.sendKissFrame(ax25);
        } catch (Exception e) {
            Log.e(TAG, "Error sending position over radio", e);
        }
    }

    /**
     * True for map markers injected from inbound APRS (see {@link #META_UVPRO_APRS}).
     */
    public static boolean isUvproAprsMarker(com.atakmap.android.maps.MapItem item) {
        return item != null && item.getMetaBoolean(META_UVPRO_APRS, false);
    }

    /**
     * Opens the APRS metadata panel (packet comment, symbol, telemetry — not generic CoT point UI).
     */
    public void openAprsDetailsPanel(com.atakmap.android.maps.MapItem item) {
        if (item == null || this.mapView == null) {
            return;
        }
        final String uid = item.getUID();
        Runnable open = () -> {
            try {
                Intent details = new Intent(
                        com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.SHOW_APRS_DETAILS);
                details.putExtra(
                        com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.EXTRA_TARGET_UID, uid);
                AtakBroadcast.getInstance().sendBroadcast(details);
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent("com.atakmap.android.maps.HIDE_MENU"));
                Log.d(TAG, "Opened APRS details panel uid=" + uid);
            } catch (Exception e) {
                Log.w(TAG, "openAprsDetailsPanel failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            open.run();
        } else {
            this.mapView.post(open);
        }
    }

    /**
     * Store formatted APRS metadata on the map marker for the details panel.
     */
    public void setAprsMarkerDetails(String uid, String detailsText) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable apply = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    item.setMetaBoolean(META_UVPRO_APRS, true);
                    if (detailsText != null && !detailsText.trim().isEmpty()) {
                        item.setMetaString(META_UVPRO_APRS_DETAILS, detailsText.trim());
                    }
                    Intent refresh = new Intent(
                            com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.REFRESH_APRS_DETAILS);
                    refresh.putExtra(
                            com.uvpro.plugin.aprs.AprsDetailsDropDownReceiver.EXTRA_TARGET_UID, uid);
                    AtakBroadcast.getInstance().sendBroadcast(refresh);
                }
            } catch (Exception e) {
                Log.w(TAG, "setAprsMarkerDetails failed uid=" + uid, e);
            }
            markAprsMapItem(uid);
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            apply.run();
        } else {
            this.mapView.post(apply);
        }
    }

    /**
     * Tag a marker as UV-PRO APRS (call after position inject when iconset may be absent).
     */
    public void markAprsMapItem(String uid) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable tag = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    item.setMetaBoolean(META_UVPRO_APRS, true);
                }
            } catch (Exception e) {
                Log.w(TAG, "markAprsMapItem failed uid=" + uid, e);
            }
            applyAprsMarkerPresentation(uid);
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            tag.run();
        } else {
            this.mapView.post(tag);
        }
    }

    /**
     * After CoT import: APRS usericon markers get label bounds and a minimal pinwheel whose
     * Details action opens CoT Info (remarks), not the TAK contact-card menu.
     */
    private void applyAprsMarkerPresentation(String uid) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        try {
            com.atakmap.android.maps.MapItem item =
                    this.mapView.getRootGroup().deepFindUID(uid);
            if (!(item instanceof com.atakmap.android.maps.Marker)) {
                return;
            }
            String iconPath = item.getMetaString(UserIcon.IconsetPath, "");
            boolean aprs = item.getMetaBoolean(META_UVPRO_APRS, false)
                    || (!iconPath.isEmpty()
                    && iconPath.startsWith(AprsSymbolMapper.ICONSET_UID));
            if (!aprs) {
                return;
            }
            item.setMetaBoolean(META_UVPRO_APRS, true);
            com.atakmap.android.maps.Marker marker =
                    (com.atakmap.android.maps.Marker) item;
            marker.setShowLabel(true);
            marker.setMinLabelRenderResolution(0.0d);
            marker.setMaxLabelRenderResolution(0.1d);
            marker.setMetaString("menu", "menus/default_item_w_type.xml");
        } catch (Exception e) {
            Log.w(TAG, "applyAprsMarkerPresentation failed uid=" + uid, e);
        }
    }

    /**
     * Dispatch a CotEvent into ATAK's internal processing.
     */
    private void dispatchCotEvent(CotEvent event) {
        // Use ATAK's internal CotMapComponent to dispatch
        try {
            CotMapComponent.getInternalDispatcher().dispatch(event);
            Log.d(TAG, "Dispatched CoT event: " + event.getUID());
            applyAprsMarkerPresentation(event.getUID());

            if (BuildConfig.DEBUG) {
                try {
                    com.atakmap.android.maps.MapItem item =
                            com.atakmap.android.maps.MapView.getMapView()
                                    .getRootGroup()
                                    .deepFindUID(event.getUID());

                    if (item != null) {
                        Log.d(TAG, "MARKER_DEBUG uid=" + item.getUID()
                                + " title=" + item.getTitle()
                                + " type=" + item.getType()
                                + " callsign=" + item.getMetaString("callsign", "NULL")
                                + " team=" + item.getMetaString("team", "NULL")
                                + " labels_on=" + item.hasMetaValue("labels_on")
                                + " hideLabel=" + item.hasMetaValue("hideLabel")
                                + " menu=" + item.getMetaString("menu", "NULL"));

                        if (item instanceof com.atakmap.android.maps.Marker) {
                            Log.d(TAG, "MARKER_DEBUG marker_class=true");
                        }
                    } else {
                        Log.d(TAG, "MARKER_DEBUG item not found uid=" + event.getUID());
                    }
                } catch (Exception dbg) {
                    Log.e(TAG, "MARKER_DEBUG failed", dbg);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to dispatch via CotMapComponent, "
                    + "trying broadcast", e);
            // Fallback: send as broadcast intent
            try {
                Intent intent = new Intent("com.atakmap.android.maps.COT_PLACED");
                intent.putExtra("xml", event.toString());
                AtakBroadcast.getInstance().sendBroadcast(intent);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to broadcast CoT event", e2);
            }
        }
    }

    /**
     * Start listening for outgoing CoT events to relay to radio.
     * Uses ATAK's PreSendProcessor to intercept all outgoing CoT.
     */
    public void startOutgoingRelay() {
        Log.d(TAG, "Outgoing CoT relay: "
                + (relayOutgoingSa ? "enabled" : "disabled"));

        outboundCommsLogger = new CommsLogger() {
            @Override
            public void dispose() {
            }

            @Override
            public void logSend(CotEvent event, String dest) {
                maybeRelayGeoChatFromCommsLogger(event);
            }

            @Override
            public void logSend(CotEvent event, String[] dests) {
                maybeRelayGeoChatFromCommsLogger(event);
            }

            @Override
            public void logReceive(CotEvent event, String src, String dest) {
                maybeSaRelayInboundNetworkCot(event);
            }
        };
        try {
            CommsMapComponent.getInstance().registerCommsLogger(outboundCommsLogger);
            Log.d(TAG, "Registered CommsLogger for outbound GeoChat capture");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register CommsLogger", e);
        }

        // Capture locally placed/edited/deleted CoT so RF-only nodes can propagate
        // point updates/deletes without requiring manual "send again".
        localCotBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                maybeRelayLocalCotBroadcast(intent);
            }
        };
        try {
            AtakBroadcast.DocumentedIntentFilter cotFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            cotFilter.addAction("com.atakmap.android.maps.COT_PLACED");
            cotFilter.addAction("com.atakmap.android.maps.COT_DELETED");
            AtakBroadcast.getInstance().registerReceiver(localCotBroadcastReceiver, cotFilter);
            Log.d(TAG, "Registered local COT_PLACED/COT_DELETED relay receiver");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register local COT relay receiver", e);
        }

        preSendProcessor = (event, toUIDs) -> {
            if (event == null) return;

            Log.d(TAG, "PreSendProcessor fired: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " toUIDs=" + (toUIDs == null ? "null" : java.util.Arrays.toString(toUIDs)));

            String type = event.getType();
            // GeoChat delivery/read receipts must never go back out over RF.
            if ("b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
                return;
            }
            boolean btConnected = btManager != null && btManager.isConnected();

            // Log GeoChat BEFORE BT gate — previous bug: early return hid whether PreSend
            // fired at all (shows up as zero lines when BLE disconnected during send tests).
            if ("b-t-f".equals(type)) {
                Log.d(TAG, "PreSend GeoChat bluetoothOk=" + btConnected
                        + " uid=" + event.getUID()
                        + " toUIDs="
                        + (toUIDs == null ? "null"
                        : java.util.Arrays.toString(toUIDs))
                        + " registeredBtechUids=" + btechContactUids.size());
            }

            if (!btConnected) return;

            if (shouldSkipOutboundRelayWasInboundInject(event.getUID())) {
                return;
            }

            // Contact-targeted send: only relay when ATAK is sending to a
            // plugin-registered radio contact.
            boolean targetsBtechContact = false;
            if (toUIDs != null && toUIDs.length > 0) {
                for (String uid : toUIDs) {
                    if (uid == null) continue;
                    String trimmed = uid.trim();
                    // Fast path: already registered in our in-memory set (populated on beacon).
                    if (btechContactUids.contains(trimmed)) {
                        targetsBtechContact = true;
                        break;
                    }
                    // Fallback: contact persisted from a previous session but no beacon yet
                    // this session — check whether it carries our PluginConnector.
                    try {
                        Contact c = Contacts.getInstance().getContactByUuid(trimmed);
                        Log.d(TAG, "PreSend UID lookup: " + trimmed + " → " + (c == null ? "null" : c.getClass().getSimpleName()));
                        if (c instanceof IndividualContact) {
                            com.atakmap.android.contact.Connector conn =
                                    ((IndividualContact) c).getConnector(
                                            PluginConnector.CONNECTOR_TYPE);
                            if (conn instanceof PluginConnector
                                    && ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(
                                            conn.getConnectionString())) {
                                targetsBtechContact = true;
                                // Also register now so future checks are fast.
                                btechContactUids.add(trimmed);
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (type == null) return;

            // GeoChat (b-t-f) often lacks reliable toUID[] in PreSendProcessor; infer
            // destination from CoT (__chat/chatgrp/chatroom/remarks) like SEND_MESSAGE/COT_PLACED.
            if (!targetsBtechContact && "b-t-f".equals(type)) {
                boolean geoRelay = shouldRelayGeoChatToRadio(event);
                Log.d(TAG, "GeoChat shouldRelayGeoChatToRadio=" + geoRelay);
                if (geoRelay) {
                    targetsBtechContact = true;
                    Log.d(TAG, "GeoChat to BTECH contact via CoT routing (weak/missing toUIDs)");
                }
            }

            if (targetsBtechContact) {
                Log.d(TAG, "Relaying contact-targeted CoT to radio: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                // GeoChat over RF as compact TYPE_CHAT so wire messageId ↔ local GeoChat line UID
                // is registered for RF delivered/read ACKs. Full TYPE_COT relay never filled that map.
                if ("b-t-f".equals(type)) {
                    if (chatBridge != null) {
                        new Thread(() -> chatBridge.relayOutboundGeoChatCotAsCompact(event)).start();
                        return;
                    }
                }
                new Thread(() -> sendCotOverRadio(event)).start();
                return;
            }

            // Optional broadcast SA relay (rate-limited) — does NOT depend on
            // a specific destination contact.
            if (!relayOutgoingSa) return;

            long now = System.currentTimeMillis();
            if (now - lastSaRelay < SA_RELAY_INTERVAL_MS) return;

            boolean shouldRelay = type.startsWith("a-f-")   // friendly SA
                    || type.startsWith("b-r-f-h")           // CASEVAC/medevac
                    || type.startsWith("b-m-p")             // markers/points
                    || type.equals("b-t-f")                 // geochat
                    || type.startsWith("u-");               // user-defined
            if (!shouldRelay) return;

            // Don't relay our own injected radio events (avoid loops / chatter).
            // Radio-injected contacts and events use ANDROID-* UIDs.
            String uid = event.getUID();
            if (uid != null && uid.startsWith("ANDROID-")) return;

            lastSaRelay = now;
            Log.d(TAG, "Relaying broadcast CoT to radio: type=" + type
                    + " uid=" + event.getUID());
            new Thread(() -> sendCotOverRadio(event)).start();
        };

        try {
            CommsMapComponent.getInstance().registerPreSendProcessor(
                    preSendProcessor);
            Log.d(TAG, "Registered PreSendProcessor for outgoing CoT relay");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PreSendProcessor", e);
        }
    }

    /**
     * SA Relay: when enabled, broadcast inbound network CoT (PLI, waypoints, routes)
     * over radio so radio-only users receive the picture.
     *
     * Guards:
     *  - SA Relay setting must be on
     *  - Radio must be connected
     *  - CoT must match the relay type filter
     *  - Must NOT be CoT that we injected from the radio (loop prevention)
     *  - Must NOT be our own PLI (beacon already handles local position)
     *  - Must respect the per-UID throttle so a fast-moving contact doesn't flood the channel
     */
    private void maybeSaRelayInboundNetworkCot(CotEvent event) {
        if (event == null) return;
        if (!isSaRelayEnabled()) return;
        if (btManager == null || !btManager.isConnected()) return;

        String type = event.getType();
        if (!isSaRelayEligibleType(type)) return;

        String uid = event.getUID();
        if (uid == null) return;

        // Skip CoT we injected from the radio — loop prevention
        if (shouldSkipOutboundRelayWasInboundInject(uid)) return;

        // Skip our own PLI — the beacon timer handles local position
        String localUid = null;
        try { localUid = MapView.getDeviceUid(); } catch (Exception ignored) {}
        if (uid.equals(localUid)) return;

        // Per-UID throttle: don't relay the same contact more than once per SA_RELAY_INTERVAL_MS
        long now = System.currentTimeMillis();
        Long lastRelay = saRelayLastSentByUid.get(uid);
        if (lastRelay != null && (now - lastRelay) < SA_RELAY_INTERVAL_MS) return;
        saRelayLastSentByUid.put(uid, now);

        if ("b-t-f".equals(type) && relayInboundNetworkGeoChatIfAllRooms(event)) {
            return;
        }

        Log.d(TAG, "SA Relay: broadcasting type=" + type + " uid=" + uid);
        new Thread(() -> sendCotOverRadio(event)).start();
    }

    private static boolean isSaRelayEligibleType(String type) {
        if (type == null || type.isEmpty()) return false;
        if ("b-t-f".equals(type)) return true;
        return SA_RELAY_TYPE_PATTERN.matcher(type).find();
    }

    private boolean relayInboundNetworkGeoChatIfAllRooms(CotEvent event) {
        if (event == null || chatBridge == null) return false;
        if (!"b-t-f".equals(event.getType())) return false;
        try {
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) return false;

            com.atakmap.coremap.cot.event.CotDetail remarks =
                    detail.getFirstChildByName(0, "remarks");
            String message = remarks != null ? remarks.getInnerText() : null;
            if (message == null || message.trim().isEmpty()) return false;

            com.atakmap.coremap.cot.event.CotDetail chat =
                    detail.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = detail.getFirstChildByName(0, "chat");
            }
            String room = chat != null ? chat.getAttribute("chatroom") : null;
            if (room == null || room.trim().isEmpty()) {
                room = ALL_CHAT_ROOMS;
            }
            if (!ALL_CHAT_ROOMS.equalsIgnoreCase(room.trim())) {
                return false;
            }

            String lineUid = event.getUID();
            Log.d(TAG, "SA Relay: forwarding network All Chat Rooms over RF uid=" + lineUid);
            chatBridge.sendChatOverRadio(localCallsign, ALL_CHAT_ROOMS, message, lineUid);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SA Relay: failed forwarding network chat", e);
            return false;
        }
    }

    /**
     * Duplicate path after core comms successfully accepts the send — often skipped for
     * plugin-native contacts ("unknown contact" path). PreSend geo hook is authoritative.
     */
    private void maybeRelayGeoChatFromCommsLogger(CotEvent event) {
        if (btManager == null || !btManager.isConnected()) return;
        if (event == null || !"b-t-f".equals(event.getType())) return;
        if (shouldSkipOutboundRelayWasInboundInject(event.getUID())) return;
        Log.d(TAG, "CommsLogger logSend b-t-f uid=" + event.getUID());
        if (!shouldRelayGeoChatToRadio(event)) return;

        String uid = event.getUID();
        Log.d(TAG, "Outbound GeoChat (CommsLogger) → compact relay: uid=" + uid);
        if (chatBridge != null) {
            new Thread(() -> chatBridge.relayOutboundGeoChatCotAsCompact(event)).start();
        } else {
            new Thread(() -> sendCotOverRadio(event)).start();
        }
    }

    /**
     * Optional RF -> TAK uplink path.
     * Active only when SA Relay and RF-to-TAK uplink are both enabled.
     */
    private void maybeRelayInboundRadioCotToTak(CotEvent event) {
        if (event == null) return;
        if (!isRfToTakUplinkEnabled()) return;
        try {
            CotMapComponent.getExternalDispatcher().dispatch(event);
            Log.d(TAG, "RF -> TAK uplink dispatched: type=" + event.getType()
                    + " uid=" + event.getUID());
        } catch (Exception e) {
            Log.w(TAG, "RF -> TAK uplink failed: " + e.getMessage());
        }
    }

    private void maybeRelayLocalCotBroadcast(Intent intent) {
        if (intent == null) return;
        if (!relayOutgoingSa) return;
        if (btManager == null || !btManager.isConnected()) return;

        final String action = intent.getAction();
        if (action == null) return;

        String cotXml = intent.getStringExtra("xml");
        if (cotXml == null || cotXml.isEmpty()) {
            cotXml = intent.getStringExtra("cotXml");
        }
        if (cotXml == null || cotXml.isEmpty()) {
            cotXml = intent.getStringExtra("cot");
        }
        if (cotXml == null || cotXml.isEmpty()) return;

        CotEvent event;
        try {
            event = CotEvent.parse(cotXml);
        } catch (Exception ignored) {
            return;
        }
        if (event == null) return;
        if (!shouldRelayLocalMapCot(event, action)) return;

        String uid = event.getUID();
        if (uid != null && shouldSkipOutboundRelayWasInboundInject(uid)) return;
        if (isDuplicateLocalRelay(event)) return;

        Log.d(TAG, "Local map CoT relay over RF: action=" + action
                + " type=" + event.getType() + " uid=" + uid);
        new Thread(() -> sendCotOverRadio(event)).start();
    }

    private boolean shouldRelayLocalMapCot(CotEvent event, String action) {
        if (event == null) return false;
        String type = event.getType();
        if (type == null || type.isEmpty()) return false;

        // Chat has its own dedicated compact relay path; avoid duplicate chat TX.
        if ("b-t-f".equals(type) || "b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
            return false;
        }

        // Skip local/self PLI updates; beacon path is authoritative.
        String uid = event.getUID();
        String localUid = null;
        try { localUid = MapView.getDeviceUid(); } catch (Exception ignored) {}
        if (uid != null && uid.equals(localUid) && type.startsWith("a-f-")) {
            return false;
        }

        // Explicitly include point/route updates and delete tombstones.
        return type.startsWith("b-m-p")
                || type.startsWith("b-m-r")
                || type.startsWith("a-f-")
                || type.startsWith("u-")
                || type.startsWith("t-x-d-d")
                || "com.atakmap.android.maps.COT_DELETED".equals(action);
    }

    private boolean isDuplicateLocalRelay(CotEvent event) {
        if (event == null) return true;
        String uid = event.getUID();
        String type = event.getType();
        String key = (uid == null ? "nouid" : uid) + "|" + (type == null ? "notype" : type);
        long now = System.currentTimeMillis();
        Long until = recentLocalRelayKeys.get(key);
        if (until != null && now < until) {
            return true;
        }
        recentLocalRelayKeys.put(key, now + LOCAL_RELAY_DEDUPE_MS);
        return false;
    }

    /**
     * UID for GeoChat {@code chatgrp}/{@code __chat} "local endpoint" when ingesting inbound DMs from
     * a background thread. {@link MapView#getDeviceUid()} may return NULL off the UI thread unless
     * {@link MapView#getMapView()} is initialized — fall back to self marker UID.
     */
    private String resolveLocalAtakUidForChatGrp(String peerSenderUid,
            String dmConversationId) {
        String local = tryResolveAtakSelfUidForChatGrp(mapView);
        if (local == null && dmConversationId != null
                && dmConversationId.startsWith(ANDROID_UID_PREFIX)) {
            Log.w(TAG, "injectChatCot: could not resolve local ATAK UID for chatgrp.uid1;"
                    + " GeoChat ACK/DM pairing may fail (conversation=" + dmConversationId + ")");
        }
        if (local != null && peerSenderUid != null && peerSenderUid.equals(local)) {
            Log.w(TAG, "injectChatCot: device uid equals peer sender " + local
                    + " — chatgrp may still be invalid");
        }
        return local;
    }

    private static String tryResolveAtakSelfUidForChatGrp(MapView instanceMapView) {
        MapView mv = null;
        try {
            mv = MapView.getMapView();
        } catch (Exception ignored) {
        }
        if (mv == null && instanceMapView != null) {
            mv = instanceMapView;
        }
        try {
            String id = MapView.getDeviceUid();
            if (!isBlank(id)) {
                return id.trim();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mv != null) {
                com.atakmap.android.maps.Marker sm = mv.getSelfMarker();
                if (sm != null) {
                    String id = sm.getUID();
                    if (!isBlank(id)) {
                        return id.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Stop and clean up.
     */
    public void dispose() {
        // Unregister PreSendProcessor — no unregister API, just null it out
        preSendProcessor = null;
        if (localCotBroadcastReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(localCotBroadcastReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregister localCotBroadcastReceiver", e);
            }
            localCotBroadcastReceiver = null;
        }
        if (outboundCommsLogger != null) {
            try {
                CommsMapComponent.getInstance()
                        .unregisterCommsLogger(outboundCommsLogger);
            } catch (Exception e) {
                Log.w(TAG, "unregisterCommsLogger", e);
            }
            outboundCommsLogger = null;
        }
        btechContactUids.clear();
        btechIdToUid.clear();
        saRelayLastSentByUid.clear();
        recentLocalRelayKeys.clear();
        Log.d(TAG, "CotBridge disposed");
    }
}
