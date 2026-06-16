package pl.waw.oledzki.jmj

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
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
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

// OpenFreeMap's "Liberty" vector style — no API key, no usage limits.
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private val WARSAW = LatLng(52.2297, 21.0122)

// Style layers we strip after load to keep the driver's street view readable:
//  • rail-in-tunnel (no depth filter) clutters underground stations like Dw. Centralny;
//  • building-3d is a fill-extrusion that renders in a depth pass painting over our flat
//    ground-level route line, so the red line can vanish "under" a building (again, Dw.
//    Centralny). Dropping the extrusion leaves the flat `building` footprint — which sits
//    below the route — for context.
// removeLayer is a no-op if an id goes away in a future style version.
private val HIDDEN_STYLE_LAYERS = listOf(
    "tunnel_major_rail",
    "tunnel_major_rail_hatching",
    "tunnel_transit_rail",
    "tunnel_transit_rail_hatching",
    "building-3d",
)

// Startup zoom, used only until the first GPS fix lets us frame the stops.
private const val DRIVER_ZOOM = 15.5
// Fraction of the screen height the previous→next stop span should occupy
// (3/4 → previous stop at 7/8·h, next stop at 1/8·h).
private const val FRAME_FRACTION = 0.75
// Straight-line metres to the leg's final stop within which the manual "next run" button shows.
private const val TERMINUS_RADIUS = 500.0
private const val CAMERA_ANIM_MS = 800
private const val ROUTE_SOURCE_ID = "route"
private const val ROUTE_LAYER_ID = "route-line"
private const val TAIL_SOURCE_ID = "route-tail"
private const val TAIL_LAYER_ID = "route-tail-line"
private const val STOPS_SOURCE_ID = "stops"
private const val STOPS_LAYER_ID = "stops-circles"
private const val PREVIEW_SOURCE_ID = "route-preview"
private const val PREVIEW_LAYER_ID = "route-preview-line"

// Red for the run itself; green marks the terminus — the final stop and the bit of shape
// past it where the next trip starts. The upcoming trip, previewed at the seam, is a muted
// dashed grey, drawn beneath the red so the active run always reads on top.
private const val ROUTE_RED = "#B60000"
private const val TERMINUS_GREEN = "#1B8A3A"
private const val PREVIEW_GREY = "#9AA0A6"

// DEBUG: on-device GPS-spoofing apps won't reach our fused LocationEngine (see the
// gps-spoofing memory), so we can feed a looping fake track instead of the real engine —
// cycling through FAKE_POINTS, one per 7 s. Hidden developer toggle, no UI; the value is the
// epoch-seconds the fake track stays on *until*, so it self-disarms. Enable for an hour with
//   adb shell settings put global jmj_fake_loc $(date -d '+1 hour' +%s)   (…0 = off now).
private const val FAKE_LOCATION_SETTING = "jmj_fake_loc"
private val FAKE_POINTS = listOf(
    doubleArrayOf(52.229545, 21.009889),
    doubleArrayOf(52.228323, 21.004010),
    doubleArrayOf(52.228328, 21.001824),
    doubleArrayOf(52.229336, 21.003154),
    doubleArrayOf(52.229423, 21.004364),
    doubleArrayOf(52.182308, 21.068533),
)

/**
 * The hidden developer fake-GPS flag. Settings.Global keeps no timestamp of its own, so the
 * value *is* the epoch-seconds the fake track expires at; fake GPS is used until then, then
 * self-disarms. Read fresh so toggling via adb takes effect on the next leg.
 */
private fun fakeLocationEnabled(context: Context): Boolean {
    val expiresAtSec = Settings.Global.getLong(context.contentResolver, FAKE_LOCATION_SETTING, 0L)
    return System.currentTimeMillis() < expiresAtSec * 1000L
}

// Empty geometry for a leg with no drawable shape (e.g. a depot move) — keeps source ids stable.
private const val EMPTY_LINE = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[]}}"""
private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

/**
 * One leg of the brigade's day, ready to render: GeoJSON for the layers and framing for the
 * camera (null if the leg has no drawable shape).
 */
private class Leg(
    val routeGeoJson: String?,
    val stopsGeoJson: String?,
    val tailGeoJson: String?,   // shape past the final stop, drawn green (null/empty if none)
    val framing: RouteFraming?,
)

/**
 * The whole day-chain: the renderable legs, the [DayPath] the [TripCursor] tracks position
 * along to advance between them, and the feed version it came from (for attribution).
 */
private class RoutePlan(val legs: List<Leg>, val dayPath: DayPath?, val feedVersion: String?)

@Composable
fun MapScreen(
    selection: BrigadeSelection,
    activeLeg: Int,
    onLegChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Drivers navigate by glancing at the map — keep the screen awake while it's shown.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

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

    // Fetch the whole brigade-day chain from S3 (cached to disk for dead zones).
    var plan by remember { mutableStateOf<RoutePlan?>(null) }
    LaunchedEffect(selection) { plan = loadRoute(context, selection) }
    // The active leg is controlled by the parent (the Kurs tab shows it); auto-advance
    // at a terminus reports the new index up via onLegChange.
    val leg = plan?.legs?.getOrNull(activeLeg)

    // Holds the map + loaded style once both are ready, so the location effect
    // below can react to permission being granted at any time.
    var ready by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    var showAttributions by remember { mutableStateOf(false) }
    // The manual "next run" button only appears as we near the current run's terminus (the
    // same proximity that previews the next trip), and hides as soon as it's used.
    var nearTerminus by remember { mutableStateOf(false) }

    Box(modifier) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            mapView.apply {
                getMapAsync { map ->
                    map.cameraPosition =
                        CameraPosition.Builder().target(WARSAW).zoom(DRIVER_ZOOM).build()
                    map.setStyle(OPENFREEMAP_STYLE) { style ->
                        HIDDEN_STYLE_LAYERS.forEach { style.removeLayer(it) }
                        ready = map to style
                    }
                }
            }
        })
        AttributionBadge(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            showAttributions = true
        }
        // Manual override at the terminus: jump to the next run if the auto-advance hasn't
        // (or shouldn't) fire. Only near the terminus, and gone once tapped — no double-tap.
        if (nearTerminus && activeLeg + 1 < (plan?.legs?.size ?: 0)) {
            Button(
                onClick = { nearTerminus = false; onLegChange(activeLeg + 1) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Text(stringResource(R.string.map_next_trip))
            }
        }
        if (showAttributions) {
            AttributionsDialog(feedVersion = plan?.feedVersion) { showAttributions = false }
        }
    }

    // (Re)draw whenever the style is ready or the active leg changes.
    LaunchedEffect(ready, leg) {
        val (_, style) = ready ?: return@LaunchedEffect
        setRoute(style, leg ?: return@LaunchedEffect)
    }

    // Once the map is ready and permission is granted, start our own location updates:
    // move the puck, re-frame the camera, and auto-advance to the next leg at the terminus.
    // Re-keyed on activeLeg so each leg gets a fresh framing + advancer.
    DisposableEffect(ready, plan, activeLeg, locationGranted) {
        val (map, style) = ready ?: return@DisposableEffect onDispose {}
        val p = plan ?: return@DisposableEffect onDispose {}
        if (!locationGranted) return@DisposableEffect onDispose {}
        val current = p.legs.getOrNull(activeLeg) ?: return@DisposableEffect onDispose {}
        // One cursor over the whole day, seeded at the leg the driver picked; it advances
        // across termini from position + heading alone (no clock). Re-seeded whenever
        // activeLeg changes, since this effect is keyed on it.
        val cursor = p.dayPath?.let { TripCursor(it, activeLeg) }
        updatePreview(style, p, null)            // start with no preview until the cursor asks for one
        var lastPreview: Int? = null

        val component = enableLocation(map, style, context)
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
                if (cursor != null) {
                    val st = cursor.update(
                        location.latitude, location.longitude,
                        if (location.hasBearing()) location.bearing.toDouble() else null,
                    )
                    if (st.previewTrip != lastPreview) {
                        updatePreview(style, p, st.previewTrip); lastPreview = st.previewTrip
                    }
                    if (st.activeTrip != activeLeg) {
                        onLegChange(st.activeTrip)   // parent re-passes; the cursor re-seeds on the new leg
                        return
                    }
                }
                // Manual "next run" button shows once we're within reach of this leg's final
                // stop — straight from the active leg's geometry, so it appears reliably at the
                // terminus even when the day-cursor is momentarily unsure (e.g. a detour).
                nearTerminus = (current.framing?.locate(location)?.distToEnd ?: Double.MAX_VALUE) <= TERMINUS_RADIUS
                val sel = current.framing?.select(location) ?: return
                if (sel.key == lastSegment) return        // same segment → leave camera put
                val height = mapView.height
                val width = mapView.width
                if (height == 0 || width == 0 || sel.spanMeters <= 0.0) return
                lastSegment = sel.key
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        frameStops(map, sel, height / density, width / density),
                    ),
                    CAMERA_ANIM_MS,
                )
            }

            override fun onFailure(exception: Exception) = Unit
        }
        if (fakeLocationEnabled(context)) {
            // Tick a synthetic fix once a second, cycling through FAKE_POINTS, into the same
            // callback the real engine would use — so the puck, framing and leg-advance all run.
            val handler = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    val now = System.currentTimeMillis()
                    val p = FAKE_POINTS[((now / 7000) % FAKE_POINTS.size).toInt()]
                    val loc = Location("fake").apply {
                        latitude = p[0]; longitude = p[1]; time = now
                    }
                    callback.onSuccess(LocationEngineResult.create(loc))
                    handler.postDelayed(this, 7000L)
                }
            }
            handler.post(tick)
            onDispose { handler.removeCallbacks(tick) }
        } else {
            requestLocationUpdates(engine, callback)
            onDispose { engine.removeLocationUpdates(callback) }
        }
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
 * Camera centred on the road between the previous and next stop, with travel pointing
 * up. Zoom is derived from MapLibre's own metres-per-pixel so the road fills
 * [FRAME_FRACTION] of the screen; we fit both its along-travel extent (height) and its
 * cross-travel extent (width) and take the looser (more zoomed-out) of the two, so a
 * hairpin that bulges sideways stays fully on screen.
 */
private fun frameStops(
    map: MapLibreMap, sel: RouteFraming.Selection, heightDp: Float, widthDp: Float,
): CameraPosition {
    val mppNow = map.projection.getMetersPerPixelAtLatitude(sel.target.latitude)
    val z = map.cameraPosition.zoom
    val zoom = minOf(
        RouteFraming.zoomForSpan(z, mppNow, sel.spanMeters, heightDp.toDouble(), FRAME_FRACTION),
        RouteFraming.zoomForSpan(z, mppNow, sel.crossMeters, widthDp.toDouble(), FRAME_FRACTION),
    )
    return CameraPosition.Builder()
        .target(sel.target)
        .zoom(zoom)
        .bearing(sel.bearing)
        .tilt(0.0)
        .build()
}

/**
 * Loads the selected brigade's whole day: fetch the line, resolve the day's service via
 * the feed calendar, take the brigade's chain, and build a renderable leg for each trip.
 * Returns null (map stays bare) if anything is missing, rather than crashing.
 */
private suspend fun loadRoute(context: Context, selection: BrigadeSelection): RoutePlan? {
    val data = try {
        fetchLine(context, selection.line)
    } catch (e: Exception) {
        Log.w("JMJ", "couldn't load line ${selection.line}", e)
        return null
    }
    val serviceId = data.resolveServiceId(selection.date) ?: return null
    val chain = data.services[serviceId]?.get(selection.brigade) ?: return null
    // The whole day as one arc-indexed path (depot moves with no shape collapse to a point);
    // trip index == leg index, so the cursor's active trip is directly the active leg.
    val dayPath = runCatching {
        DayPath.build(chain.map { data.shapes[it.shape].orEmpty() })
    }.getOrNull()
    val legs = chain.map { trip ->
        val route = data.tripLineGeoJson(trip)
        val stops = data.tripStopsGeoJson(trip)
        // A depot move may carry no shape; fromGeoJson throws on an empty path, so leave it undrawable.
        val framing = runCatching {
            RouteFraming.fromGeoJson(route, stops, data.finalClusterStart(trip))
        }.getOrNull()
        val tail = framing?.tailBeyondLastStop().orEmpty()
        Leg(
            routeGeoJson = if (framing != null) route else null,
            stopsGeoJson = if (framing != null) stops else null,
            tailGeoJson = if (tail.size >= 2) lineStringGeoJson(tail) else null,
            framing = framing,
        )
    }
    return RoutePlan(legs, dayPath, data.feedVersion)
}

/** A GeoJSON LineString Feature for a polyline (used for the green terminus tail). */
private fun lineStringGeoJson(points: List<LatLng>): String {
    val coords = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}"""
}

/** Adds the route + tail + stop layers the first time, then just swaps their data on later legs. */
private fun setRoute(style: Style, leg: Leg) {
    val route = leg.routeGeoJson ?: EMPTY_LINE
    val tail = leg.tailGeoJson ?: EMPTY_LINE
    val stops = leg.stopsGeoJson ?: EMPTY_FC
    val existing = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
    if (existing == null) {
        addRoutePreview(style, EMPTY_LINE)   // beneath the route line, so the active run reads on top
        addRouteShape(style, route)
        addRouteTail(style, tail)   // above the route line, below the stops
        addRouteStops(style, stops)
    } else {
        existing.setGeoJson(route)
        style.getSourceAs<GeoJsonSource>(TAIL_SOURCE_ID)?.setGeoJson(tail)
        style.getSourceAs<GeoJsonSource>(STOPS_SOURCE_ID)?.setGeoJson(stops)
    }
}

/**
 * Sets the previewed next trip's shape (drawn dashed/grey beneath the active run while the
 * cursor is near the seam), or clears it when [previewTrip] is null.
 */
private fun updatePreview(style: Style, plan: RoutePlan, previewTrip: Int?) {
    val geo = previewTrip?.let { plan.legs.getOrNull(it)?.routeGeoJson } ?: EMPTY_LINE
    style.getSourceAs<GeoJsonSource>(PREVIEW_SOURCE_ID)?.setGeoJson(geo)
}

/** Draws the upcoming trip faintly (dashed grey), as a heads-up at the terminus seam. */
private fun addRoutePreview(style: Style, geoJson: String) {
    style.addSource(GeoJsonSource(PREVIEW_SOURCE_ID, geoJson))
    style.addLayer(
        LineLayer(PREVIEW_LAYER_ID, PREVIEW_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(PREVIEW_GREY)),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

/** Draws the trip's shape as a line on the map. */
private fun addRouteShape(style: Style, geoJson: String) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, geoJson))
    style.addLayer(
        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(ROUTE_RED)),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

/** Draws the bit of shape past the final stop (the next-trip manoeuvre) in the terminus colour. */
private fun addRouteTail(style: Style, geoJson: String) {
    style.addSource(GeoJsonSource(TAIL_SOURCE_ID, geoJson))
    style.addLayer(
        LineLayer(TAIL_LAYER_ID, TAIL_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(TERMINUS_GREEN)),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

/**
 * Marks each stop of the trip with a circle on top of the route line; the final stop
 * (feature property `role:final`) is painted the terminus colour instead of red/white.
 */
private fun addRouteStops(style: Style, geoJson: String) {
    val isFinal = Expression.eq(Expression.get("role"), Expression.literal("final"))
    style.addSource(GeoJsonSource(STOPS_SOURCE_ID, geoJson))
    style.addLayer(
        CircleLayer(STOPS_LAYER_ID, STOPS_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(
                Expression.switchCase(
                    isFinal, Expression.color(Color.parseColor(TERMINUS_GREEN)),
                    Expression.color(Color.WHITE),
                ),
            ),
            PropertyFactory.circleStrokeColor(
                Expression.switchCase(
                    isFinal, Expression.color(Color.WHITE),
                    Expression.color(Color.parseColor(ROUTE_RED)),
                ),
            ),
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
        // Render into a TextureView, not the default SurfaceView. A SurfaceView owns a
        // separate window whose surface lifecycle is independent of the Compose view
        // tree, so when this AndroidView is re-mounted (navigate map → back → map) the
        // surface can fail to recreate in time and the map shows blank. TextureView
        // draws inside the normal view hierarchy and survives re-mounting reliably.
        val options = MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)
        MapView(context, options).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        // addObserver brings a newly-added observer up to the host's current state, so
        // a map mounted into an already-resumed activity (navigated here from the
        // brigade screen) still receives ON_START/ON_RESUME and starts rendering.
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
