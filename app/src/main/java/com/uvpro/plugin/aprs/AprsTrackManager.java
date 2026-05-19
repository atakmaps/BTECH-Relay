package com.uvpro.plugin.aprs;

import android.util.Log;

import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.uvpro.plugin.ax25.AprsParser;
import com.uvpro.plugin.ax25.AprsWeatherParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * APRS station movement trails (APRSdroid-style {@code movelog} + {@code drawTrace}).
 *
 * <p>Each mobile station gets a semi-transparent blue {@link Polyline} through recent
 * position reports. Fixed / weather stations are excluded.</p>
 */
public class AprsTrackManager {

    private static final String TAG = "UVPro.APRS.Track";

    public static final String TRACK_GROUP_NAME = "UV-Pro APRS Tracks";
    public static final String META_UVPRO_APRS_TRACK = "uvpro_aprs_track";

    /** Match APRSdroid trace color: ARGB 128,100,100,255. */
    private static final int STROKE_COLOR = 0x806464FF;
    private static final float STROKE_WEIGHT = 4f;

    /** Keep trail points for this long (APRSdroid default trim ~2 days; use 4h for map clutter). */
    private static final long MAX_TRACK_AGE_MS = 4 * 60 * 60 * 1000L;
    private static final int MAX_POINTS_PER_STATION = 500;
    /** Ignore jitter smaller than this between reports (meters). */
    private static final double MIN_SEGMENT_METERS = 12.0;

    private final MapView mapView;
    private final ConcurrentHashMap<String, StationTrack> tracks = new ConcurrentHashMap<>();
    private volatile MapGroup trackGroup;

    public AprsTrackManager(MapView mapView) {
        this.mapView = mapView;
    }

    /**
     * Record an APRS position and update the map trail.
     *
     * @param markerUid map marker UID (e.g. {@code ANDROID-KL1V-3})
     * @param displayCall shown callsign for title
     */
    public void recordPosition(String markerUid, String displayCall,
                               AprsParser.AprsPosition pos) {
        if (mapView == null || markerUid == null || pos == null) {
            return;
        }
        if (AprsWeatherParser.shouldSuppressVehicleMotion(pos)) {
            removeTrack(markerUid);
            return;
        }
        if (Double.isNaN(pos.latitude) || Double.isNaN(pos.longitude)) {
            return;
        }

        GeoPoint gp = new GeoPoint(pos.latitude, pos.longitude);
        StationTrack track = tracks.computeIfAbsent(markerUid, u -> new StationTrack(u, displayCall));
        synchronized (track) {
            if (!track.append(gp)) {
                return;
            }
            track.pruneOldPoints();
        }

        final StationTrack snapshot;
        synchronized (track) {
            snapshot = track.copyForRender();
        }
        mapView.post(() -> applyTrailOnUiThread(snapshot));
    }

    public void removeTrack(String markerUid) {
        if (markerUid == null) {
            return;
        }
        tracks.remove(markerUid);
        if (mapView == null) {
            return;
        }
        mapView.post(() -> {
            MapGroup group = ensureTrackGroup();
            if (group == null) {
                return;
            }
            MapItem item = group.deepFindUID(trackPolylineUid(markerUid));
            if (item != null) {
                group.removeItem(item);
            }
        });
    }

    private void applyTrailOnUiThread(StationTrack track) {
        MapGroup group = ensureTrackGroup();
        if (group == null || track.points.size() < 2) {
            return;
        }

        String uid = trackPolylineUid(track.markerUid);
        Polyline line = null;
        MapItem existing = group.deepFindUID(uid);
        if (existing instanceof Polyline) {
            line = (Polyline) existing;
        }
        if (line == null) {
            line = new Polyline(uid);
            line.setTitle(track.displayCall + " track");
            line.setMetaBoolean(META_UVPRO_APRS_TRACK, true);
            line.setStrokeColor(STROKE_COLOR);
            line.setStrokeWeight(STROKE_WEIGHT);
            line.setClickable(false);
            line.setZOrder(-2d);
            group.addItem(line);
        }

        GeoPoint[] pts = track.points.toArray(new GeoPoint[0]);
        line.setPoints(pts);
        Log.d(TAG, "Trail " + track.displayCall + " pts=" + pts.length);
    }

    private MapGroup ensureTrackGroup() {
        MapGroup cached = trackGroup;
        if (cached != null) {
            return cached;
        }
        MapGroup root = mapView.getRootGroup();
        if (root == null) {
            return null;
        }
        MapGroup found = root.findMapGroup(TRACK_GROUP_NAME);
        if (found == null) {
            found = new DefaultMapGroup(TRACK_GROUP_NAME);
            root.addGroup(found);
        }
        trackGroup = found;
        return found;
    }

    private static String trackPolylineUid(String markerUid) {
        return "uvpro-aprs-track-" + markerUid;
    }

    private static final class StationTrack {
        final String markerUid;
        final String displayCall;
        final List<GeoPoint> points = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();

        StationTrack(String markerUid, String displayCall) {
            this.markerUid = markerUid;
            this.displayCall = displayCall != null ? displayCall.trim() : markerUid;
        }

        boolean append(GeoPoint gp) {
            if (!points.isEmpty()) {
                GeoPoint last = points.get(points.size() - 1);
                if (GeoCalculations.distanceTo(last, gp) < MIN_SEGMENT_METERS) {
                    return false;
                }
            }
            points.add(gp);
            timestamps.add(System.currentTimeMillis());
            while (points.size() > MAX_POINTS_PER_STATION) {
                points.remove(0);
                timestamps.remove(0);
            }
            return true;
        }

        void pruneOldPoints() {
            long cutoff = System.currentTimeMillis() - MAX_TRACK_AGE_MS;
            while (!timestamps.isEmpty() && timestamps.get(0) < cutoff) {
                timestamps.remove(0);
                points.remove(0);
            }
        }

        StationTrack copyForRender() {
            StationTrack copy = new StationTrack(markerUid, displayCall);
            copy.points.addAll(points);
            copy.timestamps.addAll(timestamps);
            return copy;
        }
    }
}
