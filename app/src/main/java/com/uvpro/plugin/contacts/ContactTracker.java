package com.uvpro.plugin.contacts;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.uvpro.plugin.aprs.AprsTrackManager;
import com.uvpro.plugin.cot.CotBridge;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks radio contacts and manages their lifecycle on the ATAK map.
 *
 * Contacts are added/updated when position data or pings arrive from the radio.
 * A periodic sweep marks contacts as stale or lost and can remove very old entries.
 *
 * Thread-safe: contacts are stored in a ConcurrentHashMap and all operations
 * are safe to call from the Bluetooth read thread or the UI thread.
 */
public class ContactTracker {

    private static final String TAG = "UVPro.Contacts";

    /** Time before a contact becomes stale (align with inbound CoT stale, sparse APRS). */
    private static final long STALE_THRESHOLD_MS = 2 * 60 * 60 * 1000L;

    /** Time before a contact is marked lost (after stale). */
    private static final long LOST_THRESHOLD_MS = 3 * 60 * 60 * 1000L;

    /** Time before a lost contact is auto-removed from the plugin list. */
    private static final long REMOVE_THRESHOLD_MS = 6 * 60 * 60 * 1000L;

    /** Sweep interval for checking stale contacts (60 seconds) */
    private static final long SWEEP_INTERVAL_MS = 60_000;

    private final Map<String, RadioContact> contacts = new ConcurrentHashMap<>();
    private final CotBridge cotBridge;
    private final Handler handler;
    private boolean running = false;
    private AprsTrackManager aprsTrackManager;

    /** Listener for contact updates */
    public interface ContactListener {
        void onContactUpdated(RadioContact contact);
        void onContactRemoved(RadioContact contact);
        void onContactCountChanged(int count);
    }

    private ContactListener listener;

    public ContactTracker(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setListener(ContactListener listener) {
        this.listener = listener;
    }

    public void setAprsTrackManager(AprsTrackManager aprsTrackManager) {
        this.aprsTrackManager = aprsTrackManager;
    }

    /**
     * Start the periodic sweep timer.
     */
    public void start() {
        running = true;
        scheduleSweep();
        Log.d(TAG, "ContactTracker started");
    }

    /**
     * Stop the sweep timer and clean up.
     */
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "ContactTracker stopped. " + contacts.size()
                + " contacts tracked total");
    }

    /**
     * Update or create a contact with position data.
     * Called from PacketRouter when GPS/position data arrives.
     *
     * @param callsign Callsign of the contact
     * @param lat      Latitude in decimal degrees
     * @param lon      Longitude in decimal degrees
     * @param alt      Altitude in meters (or -1 if unknown)
     * @param speed    Speed in m/s (or -1 if unknown)
     * @param course   Course in degrees (or -1 if unknown)
     * @param battery  Battery percentage (0-100, or -1 if unknown)
     */
    public void updateContact(String callsign, double lat, double lon,
                              double alt, double speed, double course,
                              int battery) {
        String key = normalizeCallsign(callsign);
        RadioContact contact = contacts.get(key);

        if (contact == null) {
            contact = new RadioContact(callsign);
            contacts.put(key, contact);
            Log.i(TAG, "New radio contact: " + callsign);
            notifyCountChanged();
        }

        contact.updatePosition(lat, lon, alt, speed, course);
        if (battery >= 0) {
            contact.setBattery(battery);
        }
        contact.setSource(RadioContact.ContactSource.UVPRO);

        notifyContactUpdated(contact);
    }

    /**
     * Handle a ping from a contact (no position update, just liveness).
     */
    public void handlePing(String callsign) {
        String key = normalizeCallsign(callsign);
        RadioContact contact = contacts.get(key);

        if (contact == null) {
            contact = new RadioContact(callsign);
            contacts.put(key, contact);
            Log.i(TAG, "New contact from ping: " + callsign);
            notifyCountChanged();
        } else {
            contact.touch();
        }

        notifyContactUpdated(contact);
    }

    /**
     * Get a specific contact.
     */
    public RadioContact getContact(String callsign) {
        return contacts.get(normalizeCallsign(callsign));
    }

    /**
     * Liveness only: bump {@link RadioContact#touch} if the contact exists
     * (e.g. APRS telemetry with no new coordinates).
     */
    public void touchIfPresent(String callsign) {
        String key = normalizeCallsign(callsign);
        RadioContact contact = contacts.get(key);
        if (contact != null) {
            contact.touch();
            notifyContactUpdated(contact);
        }
    }

    /**
     * Get all tracked contacts (unmodifiable view).
     */
    public Collection<RadioContact> getAllContacts() {
        return Collections.unmodifiableCollection(contacts.values());
    }

    /**
     * Get count of active contacts.
     */
    public int getActiveCount() {
        int count = 0;
        for (RadioContact c : contacts.values()) {
            if (c.getStatus() == RadioContact.ContactStatus.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get total contact count.
     */
    public int getTotalCount() {
        return contacts.size();
    }

    /**
     * Sweep through contacts and update statuses.
     * Removes very old lost contacts.
     */
    private void sweep() {
        int staleCount = 0;
        int lostCount = 0;
        int removeCount = 0;

        for (Map.Entry<String, RadioContact> entry : contacts.entrySet()) {
            RadioContact contact = entry.getValue();
            contact.updateStatus(STALE_THRESHOLD_MS, LOST_THRESHOLD_MS);

            switch (contact.getStatus()) {
                case STALE:
                    staleCount++;
                    break;
                case LOST:
                    long elapsed = System.currentTimeMillis()
                            - contact.getLastSeen();
                    if (elapsed > REMOVE_THRESHOLD_MS) {
                        contacts.remove(entry.getKey());
                        removeCount++;
                        notifyContactRemoved(contact);
                    } else {
                        lostCount++;
                    }
                    break;
            }
        }

        if (staleCount > 0 || lostCount > 0 || removeCount > 0) {
            Log.d(TAG, "Sweep: active=" + getActiveCount()
                    + " stale=" + staleCount
                    + " lost=" + lostCount
                    + " removed=" + removeCount);
        }

        if (removeCount > 0) {
            notifyCountChanged();
        }
    }

    private void scheduleSweep() {
        if (!running) return;
        handler.postDelayed(() -> {
            sweep();
            scheduleSweep();
        }, SWEEP_INTERVAL_MS);
    }

    private String normalizeCallsign(String callsign) {
        return callsign.trim().toUpperCase();
    }

    private void notifyContactUpdated(RadioContact contact) {
        if (listener != null) {
            handler.post(() -> listener.onContactUpdated(contact));
        }
    }

    private void notifyContactRemoved(RadioContact contact) {
        if (aprsTrackManager != null && contact != null) {
            aprsTrackManager.removeTrack("ANDROID-" + normalizeCallsign(contact.getCallsign()));
        }
        if (listener != null) {
            handler.post(() -> listener.onContactRemoved(contact));
        }
    }

    private void notifyCountChanged() {
        if (listener != null) {
            handler.post(() -> listener.onContactCountChanged(contacts.size()));
        }
    }
}
