package de.jfricker.mygallery.data

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * GPS-Position, Land und EXIF-Aufnahmedatum eines Bildes.
 * exifDate: ms, 0 = kein EXIF-Datum.
 * country: "" = noch nicht bestimmt, "-" = kein Land / keine Position, sonst ISO-Code (z.B. "PT").
 */
data class GeoEntry(
    val dateModified: Long,
    val lat: Double,
    val lon: Double,
    val country: String,
    val exifDate: Long = 0L,
) {
    val hasLocation: Boolean get() = !lat.isNaN() && !lon.isNaN()
}

/** Einfacher Datei-Cache, damit EXIF nur einmal pro Bild gelesen wird. */
class LocationStore(private val file: File) {

    fun load(): MutableMap<Long, GeoEntry> {
        val map = HashMap<Long, GeoEntry>()
        if (!file.exists()) return map
        try {
            file.forEachLine { line ->
                val p = line.split('\t')
                // Ältere Cache-Zeilen (ohne EXIF-Datum, 5 Spalten) werden ignoriert -> Bild wird neu gelesen
                if (p.size >= 6) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    map[id] = GeoEntry(
                        dateModified = p[1].toLongOrNull() ?: 0L,
                        lat = p[2].toDoubleOrNull() ?: Double.NaN,
                        lon = p[3].toDoubleOrNull() ?: Double.NaN,
                        country = p[4],
                        exifDate = p[5].toLongOrNull() ?: 0L,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    fun save(map: Map<Long, GeoEntry>) {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((id, e) in map) {
                    w.write("$id\t${e.dateModified}\t${e.lat}\t${e.lon}\t${e.country}\t${e.exifDate}\n")
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete(); tmp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }
}

/** Ergebnis des EXIF-Lesens: Position (oder null) und Aufnahmedatum in ms (0 = keins). */
class ExifMeta(val latLon: DoubleArray?, val date: Long)

object LocationReader {
    private val EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** Liest GPS + Aufnahmedatum. Braucht ACCESS_MEDIA_LOCATION, sonst ist die Position geschwärzt. */
    fun read(context: Context, uri: Uri): ExifMeta {
        val original = try {
            MediaStore.setRequireOriginal(uri)
        } catch (_: Exception) {
            uri
        }
        return readFrom(context, original)
            ?: (if (original != uri) readFrom(context, uri) else null)
            ?: ExifMeta(null, 0L)
    }

    private fun readFrom(context: Context, uri: Uri): ExifMeta? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            val out = FloatArray(2)
            val ok = exif.getLatLong(out)
            val ll = if (ok && !(out[0] == 0f && out[1] == 0f)) doubleArrayOf(out[0].toDouble(), out[1].toDouble()) else null
            ExifMeta(ll, exifDate(exif))
        }
    } catch (_: Exception) {
        null
    }

    /** DateTimeOriginal, sonst DateTimeDigitized (inkl. Zeitzonen-Offset falls vorhanden). */
    fun exifDate(exif: ExifInterface): Long {
        val original = parse(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
        )
        if (original > 0) return original
        return parse(
            exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
        )
    }

    private fun parse(value: String?, offset: String?): Long {
        if (value.isNullOrBlank() || value.length < 19) return 0L
        return try {
            val ldt = LocalDateTime.parse(value.trim().take(19), EXIF_FMT)
            if (ldt.year < 1900) return 0L
            val zone: ZoneId = try {
                if (!offset.isNullOrBlank()) ZoneOffset.of(offset.trim()) else ZoneId.systemDefault()
            } catch (_: Exception) {
                ZoneId.systemDefault()
            }
            ldt.atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
