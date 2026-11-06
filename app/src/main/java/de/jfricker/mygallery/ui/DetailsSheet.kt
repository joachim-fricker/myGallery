package de.jfricker.mygallery.ui

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontStyle
import de.jfricker.mygallery.data.FaceRef
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.IconButton
import de.jfricker.mygallery.data.FaceStatus
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.jfricker.mygallery.data.Countries
import de.jfricker.mygallery.data.GeoEntry
import de.jfricker.mygallery.data.MediaImage
import de.jfricker.mygallery.data.yearFromPath
import de.jfricker.mygallery.data.yearOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class ExifInfo(
    val dateTimeOriginal: String?,
    val dateTimeDigitized: String?,
    val dateTime: String?,
    val camera: String?,
    val gps: String?,
)

private fun readExif(context: Context, uri: Uri): ExifInfo? {
    val uris = listOf(
        try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri },
        uri,
    )
    for (u in uris) {
        try {
            context.contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                val e = ExifInterface(pfd.fileDescriptor)
                val ll = FloatArray(2)
                val camera = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL))
                    .joinToString(" ").ifBlank { null }
                return ExifInfo(
                    dateTimeOriginal = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    dateTimeDigitized = e.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
                    dateTime = e.getAttribute(ExifInterface.TAG_DATETIME),
                    camera = camera,
                    gps = if (e.getLatLong(ll)) "%.6f, %.6f".format(ll[0], ll[1]) else null,
                )
            }
        } catch (_: Exception) {
        }
    }
    return null
}

private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
private fun fmt(millis: Long): String =
    if (millis <= 0) "– (fehlt)" else dateFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(
    image: MediaImage,
    geo: GeoEntry?,
    onDismiss: () -> Unit,
    onShowOnMap: ((lat: Double, lon: Double) -> Unit)? = null,
    personNames: List<String> = emptyList(),
    tagging: FaceTagging? = null,
) {
    var pickFor by remember { mutableStateOf<FaceRef?>(null) }
    var newFor by remember { mutableStateOf<FaceRef?>(null) }
    val context = LocalContext.current
    val exif by produceState<ExifInfo?>(null, image.id) {
        value = withContext(Dispatchers.IO) { readExif(context, image.uri) }
    }
    val country = geo?.country?.takeIf { it.isNotEmpty() && it != "-" }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
            ) {
                // Karte ganz oben
                if (geo != null && geo.hasLocation) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        MiniMap(geo.lat, geo.lon, Modifier.fillMaxSize())
                        // Transparente Fläche über der Karte: Tipp öffnet die grosse Karte
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable(enabled = onShowOnMap != null) { onShowOnMap?.invoke(geo.lat, geo.lon) }
                        )
                    }
                    if (onShowOnMap != null) {
                        TextButton(onClick = { onShowOnMap(geo.lat, geo.lon) }) {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Auf grosser Karte zeigen")
                        }
                    }
                }

                // Personen direkt unter der Karte
                if (tagging != null) {
                    val imgFaces = tagging.facesOf(image.id)
                    Section("Personen")
                    if (imgFaces.isEmpty()) {
                        InfoRow("Erkannt", "Keine Gesichter erkannt (oder Bild noch nicht gescannt)")
                    }
                    for (f in imgFaces) {
                        val name = tagging.nameOf(f.personId)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FaceAvatar(image, f, Modifier.size(48.dp))
                            Spacer(Modifier.width(12.dp))
                            val suggested = f.status == FaceStatus.SUGGESTED && name != null
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (suggested) "$name?" else name ?: "Unbenannt",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontStyle = if (name == null || suggested) FontStyle.Italic else FontStyle.Normal,
                                )
                                Text(
                                    when (f.status) {
                                        FaceStatus.CONFIRMED -> "bestätigt"
                                        FaceStatus.AUTO -> "automatisch"
                                        FaceStatus.SUGGESTED -> "Vorschlag"
                                        FaceStatus.UNNAMED -> "ohne Namen"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (suggested || f.status == FaceStatus.AUTO) {
                                IconButton(onClick = { tagging.confirm(f.id, f.personId) }) {
                                    Icon(Icons.Default.Check, contentDescription = "Ja, das ist $name")
                                }
                            }
                            TextButton(onClick = { pickFor = f }) { Text("Zuordnen …") }
                        }
                    }
                } else if (personNames.isNotEmpty()) {
                    Section("Personen")
                    InfoRow("Erkannt", personNames.joinToString(", "))
                }

                Section("Datei")
                InfoRow("Name", image.name)
                InfoRow("Speicherort", "${image.relativePath}${image.name}")
                InfoRow("Speicher", image.volume)
                InfoRow("Album (Ordner)", image.folderName)
                InfoRow("Grösse", Formatter.formatFileSize(context, image.size))
                InfoRow("Auflösung", "${image.width} × ${image.height}")
                InfoRow("Typ", image.mimeType)

                Section("Datum")
                InfoRow("Verwendet für Sortierung", fmt(image.date))
                InfoRow("Jahr des Bildes", yearOf(image.date).toString())
                InfoRow(
                    "Jahr aus Ordnername",
                    if (image.isCamera) "– (Kamera: Jahr des Bildes)" else yearFromPath(image.relativePath)?.toString() ?: "– (keins im Namen)",
                )
                InfoRow(
                    "Quelle",
                    image.dateSource.label,
                )
                InfoRow("MediaStore Aufnahme", fmt(image.dateTaken))
                InfoRow("MediaStore Hinzugefügt", fmt(image.dateAdded))
                InfoRow("MediaStore Geändert", fmt(image.dateModified * 1000L))
                InfoRow("EXIF Aufnahme (Original)", exif?.dateTimeOriginal ?: "–")
                InfoRow("EXIF Digitalisiert", exif?.dateTimeDigitized ?: "–")
                InfoRow("EXIF Datum", exif?.dateTime ?: "–")

                Section("Ort")
                InfoRow("GPS", exif?.gps ?: if (geo?.hasLocation == true) "%.6f, %.6f".format(geo.lat, geo.lon) else "–")
                InfoRow("Land", country?.let { "${Countries.flag(it)} ${Countries.name(it)} ($it)" } ?: "–")
                Section("Kamera")
                InfoRow("Gerät", exif?.camera ?: "–")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (tagging != null) {
        pickFor?.let { f ->
            PersonPickerDialog(
                "Wer ist das?",
                tagging.persons.filter { it.id != f.personId },
                onDismiss = { pickFor = null },
                onCreateNew = { typed ->
                    if (typed.isNotBlank()) tagging.assignNew(f.id, typed) else newFor = f
                    pickFor = null
                },
            ) { target ->
                tagging.assign(f.id, target.id)
                pickFor = null
            }
        }
        newFor?.let { f ->
            RenameDialog(null, onDismiss = { newFor = null }) { name ->
                tagging.assignNew(f.id, name)
                newFor = null
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 4.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Kleine, nicht bedienbare Karte mit Markierung (OpenStreetMap). */
@Composable
private fun MiniMap(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setOnTouchListener { _, _ -> true }
            controller.setZoom(14.0)
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    LaunchedEffect(lat, lon) {
        val p = GeoPoint(lat, lon)
        mapView.controller.setCenter(p)
        mapView.overlays.removeAll { it is Marker }
        mapView.overlays.add(Marker(mapView).apply {
            position = p
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ -> true }
        })
        mapView.invalidate()
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}
