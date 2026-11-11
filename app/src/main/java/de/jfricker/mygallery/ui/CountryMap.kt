package de.jfricker.mygallery.ui

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.mygallery.GalleryViewModel
import de.jfricker.mygallery.data.CountryShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon

private const val VISITED_FILL = 0x99E53935.toInt()
private const val VISITED_LINE = 0xFFB71C1C.toInt()
private const val SELECTED_LINE = 0xFF212121.toInt()

/** Kleine Inseln weglassen (Fläche der Bounding-Box in Grad²); der grösste Teil eines Landes bleibt immer. */
private const val MIN_PART_AREA = 0.02

/**
 * Weltkarte (OpenStreetMap) mit allen besuchten Ländern rot eingefärbt.
 * Tipp auf ein Land wählt es aus (auch nicht besuchte), Tipp ins Meer hebt die Auswahl auf.
 */
@Composable
fun CountryMap(
    visited: Set<String>,
    selected: String?,
    vm: GalleryViewModel,
    onSelect: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val shapes by vm.countryShapes.collectAsStateWithLifecycle()
    val currentShapes by rememberUpdatedState(shapes)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(Unit) { vm.loadCountryShapes() }

    val visitedLayer = remember { FolderOverlay() }
    val selectedLayer = remember { FolderOverlay() }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 1.5
            maxZoomLevel = 12.0
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            controller.setZoom(if (vm.countryMapZoom > 0) vm.countryMapZoom else 2.0)
            controller.setCenter(GeoPoint(vm.countryMapLat, vm.countryMapLon))
            overlays.add(visitedLayer)
            overlays.add(selectedLayer)
            // zuoberst: bekommt jeden Tipp zuerst
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    val lon = ((p.longitude + 540.0) % 360.0) - 180.0
                    currentOnSelect(currentShapes?.at(p.latitude, lon)?.code)
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
        }
    }

    // Besuchte Länder einfärben
    LaunchedEffect(shapes, visited) {
        val s = shapes ?: return@LaunchedEffect
        val parts = withContext(Dispatchers.Default) {
            visited.mapNotNull { s.byCode[it] }.flatMap { partsOf(it, dropSmall = true) }
        }
        visitedLayer.items.clear()
        parts.forEach { visitedLayer.add(polygonOf(it, VISITED_FILL, VISITED_LINE, 1.5f)) }
        mapView.invalidate()
    }

    // Ausgewähltes Land umranden
    LaunchedEffect(shapes, selected) {
        selectedLayer.items.clear()
        val shape = selected?.let { shapes?.byCode?.get(it) }
        if (shape != null) {
            partsOf(shape, dropSmall = false).forEach { selectedLayer.add(polygonOf(it, Color.TRANSPARENT, SELECTED_LINE, 3f)) }
        }
        mapView.invalidate()
    }

    // Aus der Liste gewähltes Land anzeigen
    LaunchedEffect(shapes, vm.countryFocus) {
        val code = vm.countryFocus ?: return@LaunchedEffect
        val shape = shapes?.byCode?.get(code) ?: return@LaunchedEffect
        val main = shape.polygons.maxBy { bboxArea(it[0]) }[0]
        var minLon = 180.0; var maxLon = -180.0; var minLat = 90.0; var maxLat = -90.0
        for (i in main.indices step 2) {
            minLon = minOf(minLon, main[i]); maxLon = maxOf(maxLon, main[i])
            minLat = minOf(minLat, main[i + 1]); maxLat = maxOf(maxLat, main[i + 1])
        }
        mapView.controller.setZoom(maxOf(mapView.zoomLevelDouble, 3.5))
        mapView.controller.animateTo(GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2))
        vm.countryFocus = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.countryMapZoom = mapView.zoomLevelDouble
            vm.countryMapLat = mapView.mapCenter.latitude
            vm.countryMapLon = mapView.mapCenter.longitude
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun bboxArea(ring: DoubleArray): Double {
    var minLon = 180.0; var maxLon = -180.0; var minLat = 90.0; var maxLat = -90.0
    for (i in ring.indices step 2) {
        minLon = minOf(minLon, ring[i]); maxLon = maxOf(maxLon, ring[i])
        minLat = minOf(minLat, ring[i + 1]); maxLat = maxOf(maxLat, ring[i + 1])
    }
    return (maxLon - minLon) * (maxLat - minLat)
}

private fun DoubleArray.toGeoPoints(): List<GeoPoint> =
    (0 until size / 2).map { GeoPoint(this[2 * it + 1], this[2 * it]) }

/** Ringe eines Landes als GeoPoints: je Teil (Aussenring, Löcher). Rechenintensiv -> im Hintergrund aufrufen. */
private fun partsOf(shape: CountryShapes.Shape, dropSmall: Boolean): List<Pair<List<GeoPoint>, List<List<GeoPoint>>>> {
    val largest = shape.polygons.maxBy { bboxArea(it[0]) }
    return shape.polygons
        .filter { !dropSmall || it === largest || bboxArea(it[0]) >= MIN_PART_AREA }
        .map { rings -> rings[0].toGeoPoints() to rings.drop(1).map { it.toGeoPoints() } }
}

private fun polygonOf(part: Pair<List<GeoPoint>, List<List<GeoPoint>>>, fill: Int, line: Int, width: Float): Polygon =
    Polygon().apply {
        setPoints(part.first)
        if (part.second.isNotEmpty()) setHoles(part.second)
        fillPaint.color = fill
        outlinePaint.color = line
        outlinePaint.strokeWidth = width
        setInfoWindow(null)
    }
