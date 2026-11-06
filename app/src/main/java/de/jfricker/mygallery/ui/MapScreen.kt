package de.jfricker.mygallery.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.jfricker.mygallery.GalleryViewModel
import de.jfricker.mygallery.data.MapPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.max

@Composable
fun MapScreen(
    points: List<MapPoint>,
    vm: GalleryViewModel,
    onSelect: (title: String, ids: List<Long>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnSelect by rememberUpdatedState(onSelect)

    val mapView = remember { createMapView(context) }
    val clusters = remember {
        ClusterController(mapView, scope) { ids -> currentOnSelect("${ids.size} Bilder hier", ids) }
    }

    // Kamera: gespeicherte Position wiederherstellen, sonst auf alle Bilder zoomen
    val fitted = remember { booleanArrayOf(false) }
    LaunchedEffect(points) {
        clusters.setPoints(points)
        if (vm.mapZoom > 0 && !fitted[0]) {
            mapView.controller.setZoom(vm.mapZoom)
            mapView.controller.setCenter(GeoPoint(vm.mapLat, vm.mapLon))
            fitted[0] = true
        } else if (!fitted[0] && points.isNotEmpty()) {
            fitted[0] = true
            val fit = { fitToPoints(mapView, points) }
            if (mapView.isLayoutOccurred) fit() else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
        }
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
            vm.mapZoom = mapView.zoomLevelDouble
            vm.mapLat = mapView.mapCenter.latitude
            vm.mapLon = mapView.mapCenter.longitude
            clusters.dispose()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (points.isEmpty()) {
            EmptyHint("Noch keine Bilder mit GPS-Position")
        } else {
            ExtendedFloatingActionButton(
                onClick = {
                    val ids = clusters.visibleIds()
                    if (ids.isNotEmpty()) onSelect("${ids.size} Bilder im Ausschnitt", ids)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) { Text("Bilder im Ausschnitt") }
        }
    }
}

private fun createMapView(context: Context): MapView = MapView(context).apply {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(true)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    minZoomLevel = 2.0
    maxZoomLevel = 19.0
    isVerticalMapRepetitionEnabled = false
    setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
    controller.setZoom(3.0)
    controller.setCenter(GeoPoint(45.0, 10.0))
}

private fun fitToPoints(map: MapView, points: List<MapPoint>) {
    if (points.size == 1) {
        map.controller.setZoom(12.0)
        map.controller.setCenter(GeoPoint(points[0].lat, points[0].lon))
        return
    }
    val north = points.maxOf { it.lat }
    val south = points.minOf { it.lat }
    val east = points.maxOf { it.lon }
    val west = points.minOf { it.lon }
    if (north - south < 0.01 && east - west < 0.01) {
        map.controller.setZoom(14.0)
        map.controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
    } else {
        map.zoomToBoundingBox(BoundingBox(north, east, south, west), false, 80)
    }
}

/** Fasst nahe Bilder pixelbasiert zu Clustern zusammen und zeigt das neueste Bild als Vorschau. */
private class ClusterController(
    private val map: MapView,
    private val scope: CoroutineScope,
    private val onClusterClick: (List<Long>) -> Unit,
) {
    private var points: List<MapPoint> = emptyList()
    private val markers = ArrayList<Marker>()
    private val iconJobs = ArrayList<Job>()
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { update() }

    init {
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { schedule(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { schedule(); return false }
        })
    }

    fun setPoints(p: List<MapPoint>) {
        points = p
        schedule()
    }

    fun visibleIds(): List<Long> {
        val bb = map.boundingBox
        return points.filter { bb.contains(GeoPoint(it.lat, it.lon)) }.sortedByDescending { it.date }.map { it.id }
    }

    fun dispose() {
        handler.removeCallbacks(updateRunnable)
        iconJobs.forEach { it.cancel() }
    }

    private fun schedule() {
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, 150)
    }

    private fun update() {
        if (map.width == 0 || map.height == 0) { schedule(); return }
        val res = map.resources
        val cellPx = (72 * res.displayMetrics.density).toInt()
        val proj = map.projection
        val bb = map.boundingBox
        val cells = LinkedHashMap<Long, MutableList<MapPoint>>()
        val pt = Point()
        for (p in points) {
            val gp = GeoPoint(p.lat, p.lon)
            if (!bb.contains(gp)) continue
            proj.toPixels(gp, pt)
            val key = (Math.floorDiv(pt.x, cellPx).toLong() shl 32) xor (Math.floorDiv(pt.y, cellPx).toLong() and 0xffffffffL)
            cells.getOrPut(key) { ArrayList() }.add(p)
        }

        iconJobs.forEach { it.cancel() }
        iconJobs.clear()
        map.overlays.removeAll(markers.toSet())
        markers.clear()

        for (cluster in cells.values) {
            val sorted = cluster.sortedByDescending { it.date }
            val newest = sorted.first()
            val marker = Marker(map).apply {
                position = GeoPoint(cluster.sumOf { it.lat } / cluster.size, cluster.sumOf { it.lon } / cluster.size)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ClusterIcons.make(res, null, cluster.size)
                setOnMarkerClickListener { _, _ ->
                    onClusterClick(sorted.map { it.id })
                    true
                }
            }
            markers += marker
            map.overlays.add(marker)
            iconJobs += scope.launch {
                val thumb = ThumbCache.load(map.context, newest.uri, newest.id) ?: return@launch
                marker.icon = ClusterIcons.make(res, thumb, cluster.size)
                map.invalidate()
            }
        }
        map.invalidate()
    }
}

private object ClusterIcons {
    fun make(res: Resources, thumb: Bitmap?, count: Int): Drawable {
        val d = res.displayMetrics.density
        val size = (56 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val outer = RectF(2 * d, 2 * d, size - 2 * d, size - 2 * d)
        val inner = RectF(outer.left + 3 * d, outer.top + 3 * d, outer.right - 3 * d, outer.bottom - 3 * d)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(3 * d, 0f, d, 0x66000000) }
        c.drawRoundRect(outer, 10 * d, 10 * d, shadow)

        val src = thumb?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        if (src != null) {
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = max(inner.width() / src.width, inner.height() / src.height)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    inner.left + (inner.width() - src.width * scale) / 2,
                    inner.top + (inner.height() - src.height * scale) / 2,
                )
            }
            shader.setLocalMatrix(m)
            c.drawRoundRect(inner, 7 * d, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            c.drawRoundRect(inner, 7 * d, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY })
        }

        if (count > 1) {
            val text = if (count > 999) "999+" else count.toString()
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 11 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val w = max(20 * d, tp.measureText(text) + 10 * d)
            val badge = RectF(size - w - 1 * d, size - 19 * d, size - 1 * d, size - 1 * d)
            c.drawRoundRect(badge, 9 * d, 9 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt() })
            c.drawText(text, badge.centerX(), badge.centerY() - (tp.descent() + tp.ascent()) / 2, tp)
        }
        return BitmapDrawable(res, bmp)
    }
}
