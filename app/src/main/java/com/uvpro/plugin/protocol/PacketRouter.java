package com.uvpro.plugin.protocol;

import android.util.Log;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;

import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.ax25.AprsParser;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.util.CallsignUtil;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketFragmenter;

/**
 * Routes incoming packets to the appropriate handler based on their type.
 *
 * Incoming data from the radio arrives as raw AX.25 frames. The PacketRouter:
 * 1. Decodes the AX.25 frame to extract callsign and info field
 * 2. Determines if it's an APRS packet or an UV-PRO custom packet
 * 3. Routes to the appropriate handler (CoT bridge, chat bridge, contact tracker)
 *
 * For APRS packets (from standard APRS radios / BTECH native APRS):
 *   - Position reports → ContactTracker + CotBridge
 *   - Messages → ChatBridge
 *
 * For UV-PRO custom packets (from other UV-PRO plugins):
 *   - TYPE_GPS → ContactTracker + CotBridge
 *   - TYPE_CHAT → ChatBridge
 *   - TYPE_COT → CotBridge
 *   - TYPE_PING → ContactTracker
 */
public class PacketRouter {

    private static final String TAG = "UVPro.Router";

    private final CotBridge cotBridge;
    private final ChatBridge chatBridge;
    private final ContactTracker contactTracker;
    private final PacketFragmenter.Reassembler reassembler;
    private EncryptionManager encryptionManager;

    /** Listener for packet count updates */
    private PacketCountListener packetCountListener;

    public interface PacketCountListener {
        void onPacketReceived();
    }

    public PacketRouter(CotBridge cotBridge, ChatBridge chatBridge,
                        ContactTracker contactTracker) {
        this.cotBridge = cotBridge;
        this.chatBridge = chatBridge;
        this.contactTracker = contactTracker;
        this.reassembler = new PacketFragmenter.Reassembler();
    }

    public void setPacketCountListener(PacketCountListener listener) {
        this.packetCountListener = listener;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    /**
     * Route an incoming AX.25 frame from the radio.
     * Called by BtConnectionManager on the read thread.
     */
    public void routeIncoming(byte[] ax25Data) {
        Ax25Frame frame = Ax25Frame.decode(ax25Data);
        if (frame == null) {
            Log.w(TAG, "Failed to decode AX.25 frame");
            return;
        }

        String srcCall = frame.getSrcCallsign();
        int srcSsid = frame.getSrcSsid();
        String destCall = frame.getDestCallsign();
        String info = frame.getInfoString();

        Log.d(TAG, "Received from " + srcCall + "-" + srcSsid +
                " → " + destCall + ": " + info.length() + " bytes");

        // Notify listener of received packet
        if (packetCountListener != null) {
            packetCountListener.onPacketReceived();
        }

        // Check if this is an UV-PRO custom packet
        if ("OPENRL".equals(destCall)) {
            byte[] infoField = frame.getInfoField();

            // Decrypt if encryption is enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                byte[] decrypted = encryptionManager.decrypt(infoField);
                if (decrypted != null) {
                    infoField = java.util.Arrays.copyOf(decrypted, decrypted.length);
                    java.util.Arrays.fill(decrypted, (byte) 0);
                }
                // If decryption returns null, use raw (unencrypted packet)
            }

            routeUVProPacket(srcCall, srcSsid, infoField);
            return;
        }

        // Otherwise, try to parse as standard APRS
        routeAprsPacket(srcCall, srcSsid, info);
    }

    /**
     * Route an UV-PRO custom packet.
     */
    private void routeUVProPacket(String callsign, int ssid, byte[] data) {
        UVProPacket packet = UVProPacket.decode(data);
        if (packet == null) {
            Log.w(TAG, "Failed to decode UV-PRO packet");
            return;
        }

        switch (packet.getType()) {
            case UVProPacket.TYPE_GPS:
                UVProPacket.GpsData gps =
                        UVProPacket.decodeGpsPayload(packet.getPayload());
                if (gps != null) {
                    Log.d(TAG, "GPS from " + gps.callsign +
                            ": " + gps.latitude + ", " + gps.longitude);
                    contactTracker.updateContact(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course, gps.battery);

                    final String normalized = gps.callsign.trim().toUpperCase();
                    final String uid = "ANDROID-" + normalized;

                    // Position CoT first so map marker + __group (sender team) exist before we
                    // register/link the IndividualContact (contacts list color follows MapItem).
                    cotBridge.injectPositionCot(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course,
                            gps.teamColor);

                    cotBridge.registerBtechContactUid(uid);
                    cotBridge.registerBtechContactId(normalized, uid);
                    String radioTrunc = CallsignUtil.toRadioCallsign(normalized);
                    if (radioTrunc != null && !radioTrunc.isEmpty()
                            && !radioTrunc.equalsIgnoreCase(normalized)) {
                        cotBridge.registerBtechContactId(radioTrunc, uid);
                    }

                    MapView mv = MapView.getMapView();
                    if (mv != null) {
                        mv.post(() -> linkRadioIndividualContactToMapMarker(
                                normalized, uid, 0));
                    }

                    // Notify ChatBridge so any pending/failed messages for this peer are sent.
                    chatBridge.onPeerActivity(gps.callsign);
                }
                break;

            case UVProPacket.TYPE_CHAT:
                routeChatPacket(packet.getPayload());
                break;

            case UVProPacket.TYPE_COT:
                cotBridge.injectCompressedCot(packet.getPayload());
                break;

            case UVProPacket.TYPE_PING:
                byte[] pingBytes = packet.getPayload();
                String pingCall = new String(pingBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
                java.util.Arrays.fill(pingBytes, (byte) 0);
                Log.d(TAG, "Ping from: " + pingCall);
                contactTracker.handlePing(pingCall);
                chatBridge.onPeerActivity(callsign);
                if (!callsign.equalsIgnoreCase(pingCall)) {
                    chatBridge.onPeerActivity(pingCall);
                }
                // Send ping reply if enabled
                sendPingReply();
                break;

            case UVProPacket.TYPE_ACK:
                UVProPacket.AckPayload ack =
                        UVProPacket.decodeAckPayload(packet.getPayload());
                if (ack != null) {
                    chatBridge.handleIncomingRadioChatAck(
                            ack.wireMessageId, ack.kind);
                }
                break;

            case UVProPacket.TYPE_COT_FRAGMENT:
                byte[] reassembled = reassembler.addFragment(
                        packet.getPayload());
                if (reassembled != null) {
                    Log.d(TAG, "Fragment reassembly complete: "
                            + reassembled.length + " bytes");
                    cotBridge.injectCompressedCot(reassembled);
                }
                break;

            default:
                Log.w(TAG, "Unknown UV-PRO packet type: " + packet.getType());
        }
    }

    /**
     * Route a standard APRS packet.
     */
    private void routeAprsPacket(String callsign, int ssid, String info) {
        if (info.isEmpty()) return;

        // Try position first
        AprsParser.AprsPosition pos =
                AprsParser.parsePosition(callsign, ssid, info);
        if (pos != null) {
            Log.d(TAG, "APRS position from " + callsign +
                    ": " + pos.latitude + ", " + pos.longitude);
            contactTracker.updateContact(callsign, pos.latitude,
                    pos.longitude, pos.altitude, pos.speed, pos.course, -1);
            cotBridge.injectPositionCot(callsign, pos.latitude,
                    pos.longitude, pos.altitude, pos.speed, pos.course,
                    null);
            return;
        }

        // Try message
        AprsParser.AprsMessage msg = AprsParser.parseMessage(callsign, info);
        if (msg != null) {
            Log.d(TAG, "APRS message from " + msg.fromCallsign +
                    " to " + msg.toCallsign + ": " + msg.message);
            chatBridge.injectRadioMessage(msg.fromCallsign,
                    msg.toCallsign, msg.message, 0);
            return;
        }

        Log.d(TAG, "Unhandled APRS packet from " + callsign + ": " + info);
    }

    /**
     * Decode and route a chat message from UV-PRO packet payload.
     */
    private void routeChatPacket(byte[] payload) {
        if (payload == null || payload.length < 16) return;

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(payload);
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);

        byte[] senderBytes = new byte[6];
        byte[] roomBytes = new byte[6];
        buf.get(senderBytes);
        buf.get(roomBytes);
        int messageId = buf.getInt();

        byte[] msgBytes = new byte[buf.remaining()];
        buf.get(msgBytes);

        String sender = new String(senderBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        java.util.Arrays.fill(senderBytes, (byte) 0);
        String room = new String(roomBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        java.util.Arrays.fill(roomBytes, (byte) 0);
        String message = new String(msgBytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.Arrays.fill(msgBytes, (byte) 0);

        Log.d(TAG, "Chat mid=" + messageId + " from " + sender + " [" + room + "] len=" + message.length());
        chatBridge.injectRadioMessage(sender, room, message, messageId);
        chatBridge.sendRadioChatAck(messageId, UVProPacket.ACK_KIND_DELIVERED);
    }

    /**
     * Associate {@link IndividualContact} with the CoT-driven map marker ({@link MapItem}) so
     * Contacts UI uses the peer's team tint from SA (matches native Wi‑Fi / server contacts).
     * Retries briefly if CoT processing has not yet created the marker.
     */
    private void linkRadioIndividualContactToMapMarker(final String normalized,
                                                       final String uid,
                                                       final int attempt) {
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return;
            }
            MapItem item = mv.getRootGroup().deepFindUID(uid);

            Contacts contacts = Contacts.getInstance();
            Contact existing = contacts.getContactByUuid(uid);

            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;
                if (item != null) {
                    ic.setMapItem(item);
                    ic.dispatchChangeEvent();
                    return;
                }
                if (attempt < 12) {
                    mv.postDelayed(() -> linkRadioIndividualContactToMapMarker(
                            normalized, uid, attempt + 1), 50L);
                    return;
                }
                ic.dispatchChangeEvent();
                return;
            }

            if (item == null && attempt < 12) {
                mv.postDelayed(() -> linkRadioIndividualContactToMapMarker(
                        normalized, uid, attempt + 1), 50L);
                return;
            }

            IndividualContact c = new IndividualContact(
                    normalized,
                    uid,
                    item instanceof MapItem ? item : null);

            c.addConnector(new com.atakmap.android.contact.PluginConnector(
                    ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND));

            // IpConnector with null sendIntent: makes contact visible in the SEND_LIST
            // (ContactListAdapter hard-filters on IpConnector presence) without hijacking
            // the CoT send path (isEmpty(null) → true → uniqueSelected preserved → sendCot fires).
            c.addConnector(new IpConnector((String) null));

            com.atakmap.android.preference.AtakPreferences prefs =
                    new com.atakmap.android.preference.AtakPreferences(mv.getContext());
            prefs.set("contact.connector.default." + c.getUID(),
                    com.atakmap.android.contact.PluginConnector.CONNECTOR_TYPE);

            contacts.addContact(c);
        } catch (Exception e) {
            Log.e(TAG, "linkRadioIndividualContactToMapMarker failed uid=" + uid, e);
        }
    }

    /** Send our current GPS position as a ping reply if the setting is enabled. */
    private void sendPingReply() {
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) return;
            if (!com.uvpro.plugin.ui.SettingsFragment.isPingReplyEnabled(mv.getContext())) return;

            com.atakmap.android.maps.PointMapItem self = mv.getSelfMarker();
            if (self == null) return;

            com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();
            double speedMs = 0.0, course = 0.0;
            try { speedMs = Double.parseDouble(self.getMetaString("Speed",  "0")); } catch (Exception ignored) {}
            try { course  = Double.parseDouble(self.getMetaString("course", "0")); } catch (Exception ignored) {}

            cotBridge.sendPositionOverRadio(
                    gp.getLatitude(), gp.getLongitude(),
                    gp.getAltitude(), (float) speedMs, (float) course, -1);
            Log.d(TAG, "Ping reply sent");
        } catch (Exception e) {
            Log.w(TAG, "sendPingReply failed: " + e.getMessage());
        }
    }
}
