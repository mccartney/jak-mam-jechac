package pl.waw.oledzki.jmj

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.location.permissions.PermissionsManager
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

// OpenFreeMap's "Liberty" vector style — no API key, no usage limits.
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private val WARSAW = LatLng(52.2297, 21.0122)

// Startup zoom, used only until the first GPS fix lets us frame the stops.
private const val DRIVER_ZOOM = 15.5
// Fraction of the screen height the previous→next stop span should occupy
// (3/4 → previous stop at 7/8·h, next stop at 1/8·h).
private const val FRAME_FRACTION = 0.75
private const val CAMERA_ANIM_MS = 800
// One hard-coded 504 half-trip (Os. Kabaty → Dw. Centralny, shape 3:1840) for now.
private const val ROUTE_SOURCE_ID = "route-504"
private const val ROUTE_LAYER_ID = "route-504-line"
private const val STOPS_SOURCE_ID = "stops-504"
private const val STOPS_LAYER_ID = "stops-504-circles"

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var locationGranted by remember {
        mutableStateOf(PermissionsManager.areLocationPermissionsGranted(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> locationGranted = result.values.any { it } }

    LaunchedEffect(Unit) {
        if (!locationGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    val mapView = rememberMapViewWithLifecycle()
    val framing = remember { RouteFraming.load(context) }

    // Holds the map + loaded style once both are ready, so the location effect
    // below can react to permission being granted at any time.
    var ready by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    AndroidView(modifier = modifier, factory = {
        mapView.apply {
            getMapAsync { map ->
                map.cameraPosition =
                    CameraPosition.Builder().target(WARSAW).zoom(DRIVER_ZOOM).build()
                map.setStyle(OPENFREEMAP_STYLE) { style ->
                    addRouteShape(style, context)
                    addRouteStops(style, context)
                    ready = map to style
                }
            }
        }
    })

    // Once the map is ready and permission is granted, start our own location
    // updates: move the puck and re-frame the camera each fix.
    DisposableEffect(ready, locationGranted) {
        val (map, _) = ready ?: return@DisposableEffect onDispose {}
        if (!locationGranted) return@DisposableEffect onDispose {}
        val component = enableLocation(map, ready!!.second, context)
        val engine = LocationEngineDefault.getDefaultLocationEngine(context)
        // MapLibre's projection works in logical (dp) pixels; View height is physical.
        val density = context.resources.displayMetrics.density
        // The framing only changes when we cross a stop, so keep the camera still
        // while travelling between the same pair (and it doesn't fight GPS jitter).
        var lastSegment = -1
        val callback = object : LocationEngineCallback<LocationEngineResult> {
            override fun onSuccess(result: LocationEngineResult) {
                val location = result.lastLocation ?: return
                component.forceLocationUpdate(location)
                val sel = framing.select(location) ?: return
                if (sel.key == lastSegment) return        // same segment → leave camera put
                val height = mapView.height
                if (height == 0 || sel.spanMeters <= 0.0) return
                lastSegment = sel.key
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(frameStops(map, sel, height / density)),
                    CAMERA_ANIM_MS,
                )
            }

            override fun onFailure(exception: Exception) = Unit
        }
        requestLocationUpdates(engine, callback)
        onDispose { engine.removeLocationUpdates(callback) }
    }
}

@SuppressLint("MissingPermission") // gated on locationGranted by the caller
private fun requestLocationUpdates(
    engine: LocationEngine,
    callback: LocationEngineCallback<LocationEngineResult>,
) {
    val request = LocationEngineRequest.Builder(1000L)
        .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
        .setMaxWaitTime(2000L)
        .build()
    engine.requestLocationUpdates(request, callback, Looper.getMainLooper())
    engine.getLastLocation(callback)
}

@SuppressLint("MissingPermission") // gated on locationGranted by the caller
private fun enableLocation(map: MapLibreMap, style: Style, context: Context): LocationComponent {
    val component = map.locationComponent
    if (!component.isLocationComponentActivated) {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(false)       // we feed locations ourselves
                .build(),
        )
    }
    component.isLocationComponentEnabled = true
    component.cameraMode = CameraMode.NONE          // custom framing drives the camera
    component.renderMode = RenderMode.COMPASS       // arrow follows device heading
    return component
}

/**
 * Camera that places the previous stop low on screen and the next stop high, with
 * travel pointing up. Zoom is derived from MapLibre's own metres-per-pixel so the
 * prev→next span fills [FRAME_FRACTION] of the screen height.
 */
private fun frameStops(map: MapLibreMap, sel: RouteFraming.Selection, heightDp: Float): CameraPosition {
    val mppNow = map.projection.getMetersPerPixelAtLatitude(sel.target.latitude)
    val zoom = RouteFraming.zoomForSpan(
        map.cameraPosition.zoom, mppNow, sel.spanMeters, heightDp.toDouble(), FRAME_FRACTION,
    )
    return CameraPosition.Builder()
        .target(sel.target)
        .zoom(zoom)
        .bearing(sel.bearing)
        .tilt(0.0)
        .build()
}

/** Draws the hard-coded 504 route shape (loaded from res/raw) as a line on the map. */
private fun addRouteShape(style: Style, context: Context) {
    val geoJson = context.resources
        .openRawResource(R.raw.shape_504_kabaty_centralny)
        .bufferedReader()
        .use { it.readText() }
    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, geoJson))
    style.addLayer(
        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor("#B60000")), // 504's route colour
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

/** Marks each stop of the hard-coded 504 trip with a circle on top of the route line. */
private fun addRouteStops(style: Style, context: Context) {
    val geoJson = context.resources
        .openRawResource(R.raw.stops_504_kabaty_centralny)
        .bufferedReader()
        .use { it.readText() }
    style.addSource(GeoJsonSource(STOPS_SOURCE_ID, geoJson))
    style.addLayer(
        CircleLayer(STOPS_LAYER_ID, STOPS_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(Color.WHITE),
            PropertyFactory.circleStrokeColor(Color.parseColor("#B60000")),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}

/** Creates a [MapView] tied to the current lifecycle, forwarding the callbacks it requires. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    // MapLibre must be initialised before any MapView is constructed.
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}
