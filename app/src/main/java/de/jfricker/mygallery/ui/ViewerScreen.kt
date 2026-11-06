package de.jfricker.mygallery.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.jfricker.mygallery.data.Countries
import de.jfricker.mygallery.data.GeoEntry
import de.jfricker.mygallery.data.MediaImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ViewerScreen(
    images: List<MediaImage>,
    startIndex: Int,
    geo: Map<Long, GeoEntry>,
    onBack: () -> Unit,
    onShowOnMap: ((lat: Double, lon: Double) -> Unit)? = null,
    personNames: (Long) -> List<String> = { emptyList() },
    tagging: FaceTagging? = null,
) {
    BackHandler(onBack = onBack)
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, images.lastIndex)) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    var showUi by remember { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { images[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                image = images[page],
                onTap = { showUi = !showUi },
                onZoomChanged = { z -> if (page == pagerState.currentPage) zoomed = z },
            )
        }

        val current = images[pagerState.currentPage.coerceIn(0, images.lastIndex)]
        if (showUi) {
            val dateText = remember(current.id) {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .format(Instant.ofEpochMilli(current.date).atZone(ZoneId.systemDefault()))
            }
            val country = geo[current.id]?.country?.takeIf { it.isNotEmpty() && it != "-" }
            val subtitle = buildString {
                append(current.folderName)
                if (country != null) append("  ·  ${Countries.flag(country)} ${Countries.name(country)}")
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(dateText, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { showDetails = true }) {
                    Icon(Icons.Default.Info, contentDescription = "Details", tint = Color.White)
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, current.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Bild teilen"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Teilen", tint = Color.White)
                }
            }
            Text(
                "${pagerState.currentPage + 1} / ${images.size}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (showDetails) {
            DetailsSheet(
                current,
                geo[current.id],
                onDismiss = { showDetails = false },
                personNames = personNames(current.id),
                tagging = tagging,
                onShowOnMap = onShowOnMap?.let { show -> { lat: Double, lon: Double -> showDetails = false; show(lat, lon) } },
            )
        }
    }
}

private fun clampOffset(o: Offset, scale: Float, w: Int, h: Int): Offset {
    val maxX = w * (scale - 1f) / 2f
    val maxY = h * (scale - 1f) / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

@Composable
private fun ZoomableImage(
    image: MediaImage,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val thumb = remember(image.id) { ThumbCache.peek(image.id) }
    val full by produceState(initialValue = null as android.graphics.Bitmap?, image.id) {
        value = FullImageLoader.load(context, image.uri)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val isZoomed = scale > 1.01f
    LaunchedEffect(isZoomed) { onZoomChanged(isZoomed) }

    val shown = full ?: thumb
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val s = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = s
                            offset = clampOffset((center - tap) * (s - 1f), s, size.width, size.height)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // Ein Finger ohne Zoom -> Pager wischt; sonst zoomen/verschieben
                        if (pressed >= 2 || scale > 1.01f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero
                            else clampOffset(offset + event.calculatePan(), newScale, size.width, size.height)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            val bmp = remember(shown) { shown.asImageBitmap() }
            Image(
                bmp,
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
