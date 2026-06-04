package pl.waw.oledzki.jmj

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.location.permissions.PermissionsManager
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// OpenFreeMap's "Liberty" vector style — no API key, no usage limits.
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private val WARSAW = LatLng(52.2297, 21.0122)

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

    // Holds the map + loaded style once both are ready, so the location effect
    // below can react to permission being granted at any time.
    var ready by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    AndroidView(modifier = modifier, factory = {
        mapView.apply {
            getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder().target(WARSAW).zoom(11.0).build()
                map.setStyle(OPENFREEMAP_STYLE) { style -> ready = map to style }
            }
        }
    })

    LaunchedEffect(ready, locationGranted) {
        val (map, style) = ready ?: return@LaunchedEffect
        if (locationGranted) enableLocation(map, style, context)
    }
}

@SuppressLint("MissingPermission") // gated on locationGranted by the caller
private fun enableLocation(map: MapLibreMap, style: Style, context: Context) {
    val component = map.locationComponent
    if (!component.isLocationComponentActivated) {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(true)
                .build(),
        )
    }
    component.isLocationComponentEnabled = true
    component.cameraMode = CameraMode.TRACKING      // keep the camera on the driver
    component.renderMode = RenderMode.COMPASS       // arrow follows device heading
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
