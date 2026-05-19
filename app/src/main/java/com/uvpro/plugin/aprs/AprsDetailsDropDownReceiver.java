package com.uvpro.plugin.aprs;

import android.content.Context;
import android.content.Intent;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import android.util.Log;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.cot.CotBridge;

/**
 * APRS-specific details panel: shows comment/telemetry/symbol data from the RF packet,
 * not the generic ATAK CoT point editor (range/bearing, unit type, etc.).
 */
public class AprsDetailsDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener {

    public static final String SHOW_APRS_DETAILS =
            "com.uvpro.plugin.SHOW_APRS_DETAILS";
    /** Fired when stored APRS metadata changes; refreshes an open panel. */
    public static final String REFRESH_APRS_DETAILS =
            "com.uvpro.plugin.REFRESH_APRS_DETAILS";
    public static final String EXTRA_TARGET_UID = "targetUID";

    private static final String TAG = "UVPro.APRS.Details";

    private final Context pluginContext;
    private final ContactTracker contactTracker;

    private View panelView;
    private TextView titleView;
    private TextView bodyView;
    private String openUid;
    private boolean dropDownVisible;

    public AprsDetailsDropDownReceiver(MapView mapView, Context pluginContext,
                                       ContactTracker contactTracker) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.contactTracker = contactTracker;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        String uid = intent.getStringExtra(EXTRA_TARGET_UID);
        if (uid == null || uid.isEmpty()) {
            return;
        }

        if (REFRESH_APRS_DETAILS.equals(action)) {
            if (dropDownVisible && uid.equals(openUid)) {
                refreshBody(uid);
            }
            return;
        }
        if (!SHOW_APRS_DETAILS.equals(action)) {
            return;
        }

        MapItem item = getMapView().getRootGroup().deepFindUID(uid);
        if (item == null || !CotBridge.isUvproAprsMarker(item)) {
            return;
        }

        ensurePanel();
        openUid = uid;
        String callsign = item.getMetaString("callsign", item.getTitle());
        if (callsign == null || callsign.isEmpty()) {
            callsign = uid.startsWith("ANDROID-") ? uid.substring("ANDROID-".length()) : uid;
        }
        titleView.setText(callsign);
        refreshBody(uid);

        try {
            setSelected(item, "asset:/icons/outline.png");
        } catch (Exception ignored) {
        }

        showDropDown(panelView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);
    }

    private void refreshBody(String uid) {
        if (bodyView == null || uid == null) {
            return;
        }
        MapItem item = getMapView().getRootGroup().deepFindUID(uid);
        if (item == null) {
            return;
        }
        String callsign = item.getMetaString("callsign", item.getTitle());
        if (callsign == null || callsign.isEmpty()) {
            callsign = uid.startsWith("ANDROID-") ? uid.substring("ANDROID-".length()) : uid;
        }
        String body = item.getMetaString(CotBridge.META_UVPRO_APRS_DETAILS, "");
        if (body.isEmpty()) {
            RadioContact rc = contactTracker != null
                    ? contactTracker.getContact(callsign) : null;
            if (rc != null && rc.getLastAprsDetailsText() != null) {
                body = rc.getLastAprsDetailsText();
            }
        }
        if (body.isEmpty()) {
            body = "No APRS metadata stored yet.\n\n"
                    + "Wait for the next position packet from this station.";
        }
        bodyView.setText(body);
        Log.d(TAG, "Refreshed APRS details uid=" + uid + " len=" + body.length());
    }

    private void ensurePanel() {
        if (panelView != null) {
            return;
        }
        int layoutId = pluginContext.getResources().getIdentifier(
                "aprs_details_dropdown", "layout", pluginContext.getPackageName());
        panelView = LayoutInflater.from(pluginContext).inflate(layoutId, null);
        titleView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("aprs_details_title", "id", pluginContext.getPackageName()));
        bodyView = panelView.findViewById(pluginContext.getResources()
                .getIdentifier("aprs_details_body", "id", pluginContext.getPackageName()));
        if (bodyView != null) {
            bodyView.setMovementMethod(new ScrollingMovementMethod());
        }
    }

    @Override
    public void onDropDownVisible(boolean v) {
        dropDownVisible = v;
        if (!v) {
            openUid = null;
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void disposeImpl() {
        panelView = null;
        titleView = null;
        bodyView = null;
    }
}
