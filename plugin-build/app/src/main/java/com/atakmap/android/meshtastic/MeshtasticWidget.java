package com.atakmap.android.meshtastic;

import android.content.Context;
import android.graphics.Color;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.coremap.log.Log;
import com.atakmap.android.meshtastic.plugin.R;


public class MeshtasticWidget extends MarkerIconWidget {
    private final static int ICON_WIDTH = 32;
    private final static int ICON_HEIGHT = 32;

    public static final String TAG = "meshtasticWidget";

    private MapView _mapView = null;
    private Context _plugin;


    public MeshtasticWidget(Context plugin, MapView mapView) {
        _mapView = mapView;
        _plugin = plugin;

        RootLayoutWidget root = (RootLayoutWidget) mapView.getComponentExtra("rootLayoutWidget");
        LinearLayoutWidget brLayout = root.getLayout(RootLayoutWidget.BOTTOM_RIGHT);
        brLayout.addWidget(this);
        setIcon("red");
    }

    public void setIcon(String color) {
        String imageUri;
        int iconColor;

        if (color.equalsIgnoreCase("red")) {
            imageUri = "android.resource://com.atakmap.android.meshtastic.plugin/" + R.drawable.ic_red;
            iconColor = Color.RED;
        } else if (color.equalsIgnoreCase("blue")) {
            // Blue indicates active transfer (receiving/sending chunked data)
            imageUri = "android.resource://com.atakmap.android.meshtastic.plugin/" + R.drawable.ic_green;
            iconColor = Color.BLUE;
        } else if (color.equalsIgnoreCase("yellow")) {
            // Yellow indicates active transfer (alternative color)
            imageUri = "android.resource://com.atakmap.android.meshtastic.plugin/" + R.drawable.ic_green;
            iconColor = Color.YELLOW;
        } else {
            imageUri = "android.resource://com.atakmap.android.meshtastic.plugin/" + R.drawable.ic_green;
            iconColor = Color.GREEN;
        }

        Log.d(TAG, "imageURi " + imageUri + ", color=" + color);
        Icon.Builder builder = new Icon.Builder();
        builder.setAnchor(0, 0);
        builder.setColor(Icon.STATE_DEFAULT, iconColor);
        builder.setSize(ICON_WIDTH, ICON_HEIGHT);
        builder.setImageUri(Icon.STATE_DEFAULT, imageUri);

        Icon icon = builder.build();
        setIcon(icon);
    }

    public void destroy() {
        RootLayoutWidget root = (RootLayoutWidget) _mapView.getComponentExtra("rootLayoutWidget");
        LinearLayoutWidget brLayout = root.getLayout(RootLayoutWidget.BOTTOM_RIGHT);
        brLayout.removeWidget(this);
    }
}
