package com.uvpro.plugin.cot;

import android.util.Log;

import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Builds CoT (Cursor-on-Target) events for ATAK ingestion.
 *
 * CoT is XML-based situational awareness protocol. Key structure:
 * <pre>
 * &lt;event uid="..." type="..." how="..." time="..." start="..." stale="..."&gt;
 *   &lt;point lat="..." lon="..." hae="..." ce="..." le="..."/&gt;
 *   &lt;detail&gt;
 *     &lt;contact callsign="..."/&gt;
 *     &lt;__group name="..." role="..."/&gt;
 *     &lt;status battery="..."/&gt;
 *     &lt;track speed="..." course="..."/&gt;
 *   &lt;/detail&gt;
 * &lt;/event&gt;
 * </pre>
 */
public class CotBuilder {

    private static final String TAG = "UVPro.CotBuilder";

    /** Stale interval for radio contacts: 5 minutes */
    private static final long STALE_MILLIS = 5 * 60 * 1000L;

    /** ISO 8601 date format for CoT timestamps */
    private static final SimpleDateFormat COT_DATE_FORMAT;
    static {
        COT_DATE_FORMAT = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Build a CoT event for a radio contact position.
     *
     * @param callsign  Callsign of the radio contact
     * @param lat       Latitude in decimal degrees
     * @param lon       Longitude in decimal degrees
     * @param alt       Altitude in meters (HAE), or 9999999 if unknown
     * @param speed     Speed in m/s, or -1 if unknown
     * @param course    Course in degrees, or -1 if unknown
     * @return CotEvent ready for dispatch to ATAK
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course, "Cyan");
    }

    /**
     * Build a CoT event for a radio contact position with configurable team color.
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor) {
        CotEvent event = new CotEvent();

        String normalizedCall = callsign.trim().toUpperCase();
        String uid = "ANDROID-" + normalizedCall;
        event.setUID(uid);
        event.setType("a-f-G-U-C"); // friendly ground unit combat
        event.setHow("m-g");          // machine GPS

        long now = System.currentTimeMillis();
        String timeStr = formatCotTime(now);
        String staleStr = formatCotTime(now + STALE_MILLIS);

        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        // HAE: Height Above Ellipsoid
        double hae = (alt >= 0 && alt < 99999) ? alt : CotPoint.UNKNOWN;
        event.setPoint(new CotPoint(lat, lon, hae,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");

        // Contact element
        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", normalizedCall);
        detail.addChild(contact);

        // Group element — use configured team color
        CotDetail group = new CotDetail("__group");
        group.setAttribute("name", teamColor != null ? teamColor : "Cyan");
        group.setAttribute("role", "Team Member");
        detail.addChild(group);

        // Track element (speed/course)
        if (speed >= 0 || course >= 0) {
            CotDetail track = new CotDetail("track");
            if (speed >= 0) {
                track.setAttribute("speed",
                        String.format(Locale.US, "%.1f", speed));
            }
            if (course >= 0) {
                track.setAttribute("course",
                        String.format(Locale.US, "%.1f", course));
            }
            detail.addChild(track);
        }

        // Remark with source info
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "UV-PRO Radio");
        detail.addChild(remarks);

        event.setDetail(detail);

        return event;
    }

    /**
     * Build a GeoChat CoT event.
     *
     * GeoChat messages use type "b-t-f" (bits-text-free).
     *
     * @param senderUid  UID of the sender
     * @param senderCall Callsign of the sender
     * @param message    Chat message text
     * @param chatRoom   Chat room identifier (use "All Chat Rooms" for broadcast)
     * @return CotEvent for the chat message
     */
    /**
     * For inbound DMs, {@link com.atakmap.android.chat.ChatMessageParser} expects
     * {@code __chat} {@code chatroom}/{@code id} to equal {@link MapView#getDeviceUid} when the peer
     * is the sender; it then rewrites parsed {@code conversationId} to the peer contact UID so the
     * thread matches Contacts. If chatroom holds only the peer UID, ATAK skips that rewrite,
     * {@code sendStatusMessage} targets the wrong contact, and GeoChat IDs stay
     * {@code ANDROID-VETTE.ANDROID-VETTE}.
     *
     * @param localDeviceUidIfDm When non-null at same time {@code dmPeerConversationUid} is
     *                           {@code ANDROID-*}, becomes {@code chatroom}/{@code id}/{@link CotEvent} UID suffix
     *                           and remarks {@code to}; sender stays {@code senderUid} in {@code link}.
     */
    public static CotEvent buildChatCot(String senderUid, String senderCall,
                                        String message, String dmPeerConversationUid,
                                        long uniqueSuffix,
                                        String localDeviceUidIfDm) {
        CotEvent event = new CotEvent();

        // CHAT3 (CoT with <chatgrp>): ChatMessageParser.getConversationUid reads __chat "chatroom"
        // and resolves via getFirstContactWithCallsign — must be the peer's callsign (e.g. VETTE),
        // not ANDROID-1729… or lookup fails and GeoChat routing breaks.
        String chatroomAttr = dmPeerConversationUid;
        String idAttr = dmPeerConversationUid;
        if (localDeviceUidIfDm != null && !localDeviceUidIfDm.isEmpty()
                && dmPeerConversationUid != null && dmPeerConversationUid.startsWith("ANDROID-")) {
            idAttr = localDeviceUidIfDm.trim();
            chatroomAttr = senderCall != null ? senderCall.trim() : dmPeerConversationUid;
        }

        String uid = "GeoChat." + senderUid + "." + idAttr + "." + uniqueSuffix;
        event.setUID(uid);
        event.setType("b-t-f");
        event.setHow("h-g-i-g-o"); // human-generated

        long now = System.currentTimeMillis();
        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        // Point is required but not meaningful for chat — use 0,0
        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");

        // __chat element
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("parent", "RootContactGroup");
        chat.setAttribute("groupOwner", "false");
        chat.setAttribute("messageId", uid);
        chat.setAttribute("chatroom", chatroomAttr);
        chat.setAttribute("id", idAttr);
        chat.setAttribute("senderCallsign", senderCall);

        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", senderUid);
        String uid1 = idAttr;
        if (localDeviceUidIfDm != null && !localDeviceUidIfDm.isEmpty()
                && dmPeerConversationUid != null && dmPeerConversationUid.startsWith("ANDROID-")) {
            uid1 = localDeviceUidIfDm.trim();
        }
        chatgrp.setAttribute("uid1", uid1);
        chatgrp.setAttribute("id", dmPeerConversationUid != null ? dmPeerConversationUid : idAttr);
        chat.addChild(chatgrp);

        detail.addChild(chat);

        // link element
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", senderUid);
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        detail.addChild(link);

        // remarks element — contains the actual message
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "BAO.F.ATAK." + senderUid);
        // Keep wire "to" as the peer thread; __chat id/chatroom carry local surface for CHAT3 parser remap.
        remarks.setAttribute("to",
                (dmPeerConversationUid != null && !dmPeerConversationUid.isEmpty())
                        ? dmPeerConversationUid : chatroomAttr);
        remarks.setAttribute("time", formatCotTime(now));
        remarks.setInnerText(message);
        detail.addChild(remarks);

        event.setDetail(detail);

        return event;
    }

    /**
     * GeoChat delivered/read receipt CoT ({@code b-t-f-d} / {@code b-t-f-r}) referencing an
     * existing chat line by {@code __chat messageId} (the original GeoChat event UID).
     *
     * @param localDeviceUidOrNull from {@link MapView#getDeviceUid()} (cached on UI thread); improves
     *                             parity with {@link #buildChatCot} DM {@code __chat}/{@code chatgrp}.
     * @param localCallsignOrNull  local operator callsign for {@code __chat chatroom} on DM sends
     */
    public static CotEvent buildGeoChatReceiptCot(String referencedMessageLineUid,
                                                  boolean readNotDelivered,
                                                  String localDeviceUidOrNull,
                                                  String localCallsignOrNull) {
        if (referencedMessageLineUid == null || referencedMessageLineUid.trim().isEmpty()) {
            return null;
        }
        final String lineUid = referencedMessageLineUid.trim();
        final String[] triplet = parseGeoChatUidTriplet(lineUid);

        CotEvent event = new CotEvent();

        long now = System.currentTimeMillis();
        // GeoChatService.onCotEvent resolves receipts via chatDb.getChatMessage(cotEvent.getUID()).
        // Native sendStatusMessage sets cotEvent.setUID(messageId) where messageId is the bare
        // UUID from the chat bundle — the LAST segment of the GeoChat.* UID — NOT the full string.
        // Using the full GeoChat.* UID means the DB lookup always misses.
        String receiptUid = extractMessageIdSuffix(lineUid);
        event.setUID(receiptUid != null ? receiptUid : lineUid);
        event.setType(readNotDelivered ? "b-t-f-r" : "b-t-f-d");
        event.setHow("h-e");

        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("parent", "RootContactGroup");
        chat.setAttribute("groupOwner", "false");
        chat.setAttribute("messageId", lineUid);

        if (triplet != null) {
            String senderUid = triplet[0];
            String threadId = triplet[1];
            String localDev = localDeviceUidOrNull != null
                    ? localDeviceUidOrNull.trim() : "";
            String callsign = localCallsignOrNull != null
                    ? localCallsignOrNull.trim() : "";

            if (threadId != null && threadId.startsWith("ANDROID-") && !localDev.isEmpty()) {
                // Mirror buildChatCot DM: chatroom = local callsign, id = local device UID.
                String chatroom = !callsign.isEmpty() ? callsign
                        : threadId.substring("ANDROID-".length());
                chat.setAttribute("chatroom", chatroom);
                chat.setAttribute("id", localDev);
            } else if (threadId != null && !threadId.isEmpty()) {
                chat.setAttribute("chatroom", threadId);
                chat.setAttribute("id", senderUid != null ? senderUid : localDev);
            }

            CotDetail chatgrp = new CotDetail("chatgrp");
            chatgrp.setAttribute("uid0", senderUid != null ? senderUid : "");
            String uid1 = threadId != null ? threadId : "";
            if (threadId != null && threadId.startsWith("ANDROID-") && !localDev.isEmpty()) {
                uid1 = localDev;
            }
            chatgrp.setAttribute("uid1", uid1);
            chatgrp.setAttribute("id", threadId != null ? threadId : "");
            chat.addChild(chatgrp);
        }

        detail.addChild(chat);

        if (triplet != null) {
            String senderUid = triplet[0];
            String threadId = triplet[1];

            CotDetail link = new CotDetail("link");
            link.setAttribute("uid", senderUid != null ? senderUid : "");
            link.setAttribute("type", "a-f-G-U-C");
            link.setAttribute("relation", "p-p");
            detail.addChild(link);

            CotDetail remarks = new CotDetail("remarks");
            remarks.setAttribute("source", "BAO.F.ATAK."
                    + (senderUid != null ? senderUid : ""));
            remarks.setAttribute("to", threadId != null ? threadId : "");
            remarks.setAttribute("time", formatCotTime(now));
            remarks.setInnerText("");
            detail.addChild(remarks);
        }

        event.setDetail(detail);
        return event;
    }

    /**
     * Extracts the bare messageId (last dot-segment) from a {@code GeoChat.*} UID.
     * For {@code GeoChat.ANDROID-e39bc5456040ade8.ANDROID-JUNIOR.8f7974d9-3cd3-4355-afaa-4755219d6243}
     * returns {@code 8f7974d9-3cd3-4355-afaa-4755219d6243}.
     * Returns null if the input is not a GeoChat.* string or has no suffix.
     */
    private static String extractMessageIdSuffix(String geoChatLineUid) {
        if (geoChatLineUid == null || !geoChatLineUid.startsWith("GeoChat.")) {
            return null;
        }
        int lastDot = geoChatLineUid.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= geoChatLineUid.length() - 1) {
            return null;
        }
        String suffix = geoChatLineUid.substring(lastDot + 1);
        return suffix.isEmpty() ? null : suffix;
    }

    /**
     * Parses {@code GeoChat.{senderUid}.{threadOrRoom}.{uniqueSuffix}} into {@code [senderUid, threadOrRoom]}.
     */
    private static String[] parseGeoChatUidTriplet(String geoChatLineUid) {        if (geoChatLineUid == null || !geoChatLineUid.startsWith("GeoChat.")) {
            return null;
        }
        String rest = geoChatLineUid.substring("GeoChat.".length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= rest.length() - 1) {
            return null;
        }
        rest = rest.substring(0, lastDot);
        int prevDot = rest.lastIndexOf('.');
        if (prevDot <= 0 || prevDot >= rest.length() - 1) {
            return null;
        }
        String senderUid = rest.substring(0, prevDot);
        String threadId = rest.substring(prevDot + 1);
        return new String[]{senderUid, threadId};
    }

    /**
     * Format a timestamp as CoT-compatible ISO 8601 UTC string.
     */
    public static String formatCotTime(long millis) {
        synchronized (COT_DATE_FORMAT) {
            return COT_DATE_FORMAT.format(new Date(millis));
        }
    }

    /**
     * Compress a CoT XML string with GZIP.
     */
    public static byte[] compressCot(String cotXml) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(baos);
            gzip.write(cotXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            gzip.close();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to compress CoT", e);
            return null;
        }
    }

    /**
     * Decompress a GZIP-compressed CoT XML.
     */
    public static String decompressCot(byte[] compressed) {
        byte[] buf = new byte[512];
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            GZIPInputStream gzip = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int n;
            while ((n = gzip.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            gzip.close();
            return new String(baos.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decompress CoT", e);
            return null;
        }
    }
}
