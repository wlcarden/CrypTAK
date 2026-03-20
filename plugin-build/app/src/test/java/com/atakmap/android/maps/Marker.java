package com.atakmap.android.maps;

import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * Test stub shadowing com.atakmap:main.jar's Marker.
 *
 * Prevents potential native-initializer failures when Mockito's ByteBuddy
 * instruments the class for @Mock Marker fields. Only declares the methods
 * used in tests and production code that runs during tests.
 */
public class Marker {

    public GeoPoint getPoint() { return null; }

    public String getUID() { return null; }
}
