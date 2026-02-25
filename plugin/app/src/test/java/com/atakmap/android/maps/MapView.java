package com.atakmap.android.maps;

import android.content.Context;

/**
 * Test stub shadowing com.atakmap:main.jar's MapView.
 *
 * The real MapView has a static initializer that calls System.loadLibrary(),
 * which fails in JVM unit tests. This stub is placed in test sources so the
 * compiler and class loader prefer it over the SDK jar, allowing
 * Mockito.mockStatic(MapView.class) and @Mock MapView to work without
 * UnsatisfiedLinkError.
 *
 * Only declares the methods that production code or tests actually call.
 * All behaviour is provided by Mockito at runtime.
 */
public class MapView {

    /** Shadows ATAK SDK's public static _mapView field used via static import in production. */
    public static MapView _mapView = null;

    public static MapView getMapView() { return null; }

    public Context getContext() { return null; }

    public void post(Runnable r) { }

    public Marker getSelfMarker() { return null; }

    public String getDeviceCallsign() { return null; }

    // Return types resolved from the ATAK SDK jar (pure Java stubs, no native init).
    // Called only from MeshtasticMapComponent, which is mockStatic'd in tests, so
    // these methods are never actually invoked at test runtime.
    public MapGroup getRootGroup() { return null; }

    public MapEventDispatcher getMapEventDispatcher() { return null; }
}
