package com.atakmap.coremap.maps.coords;

/**
 * Test stub shadowing com.atakmap:main.jar's GeoPoint.
 *
 * Prevents potential native-initializer failures when Mockito instruments
 * the class for @Mock GeoPoint fields. Only declares the three methods called
 * in CotEventProcessor.parseCotEvent() and its unit tests.
 */
public class GeoPoint {

    public double getAltitude() { return 0; }

    public double getLatitude() { return 0; }

    public double getLongitude() { return 0; }
}
