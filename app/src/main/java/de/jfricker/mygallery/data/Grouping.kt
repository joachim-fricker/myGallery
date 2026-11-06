package de.jfricker.mygallery.data

import android.net.Uri
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Album(
    val path: String,
    val name: String,
    val images: List<MediaImage>,
    /** Jahr, zu dem das Album gehört */
    val year: Int,
    val isCamera: Boolean,
) {
    /** Eindeutig: der Kamera-Ordner erscheint einmal pro Jahr */
    val key: String get() = "$path|$year"
}

data class YearAlbums(val year: Int, val albums: List<Album>) {
    val imageCount: Int get() = albums.sumOf { it.images.size }
}

data class CountryGroup(
    val code: String,
    val name: String,
    val flag: String,
    val images: List<MediaImage>,
)

data class MapPoint(val id: Long, val uri: Uri, val lat: Double, val lon: Double, val date: Long)

fun yearOf(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).year

/**
 * Jeder Ordner = ein Album. Jahr = Jahr im Ordnernamen/Pfad (z.B. "2015_Zinnen"),
 * sonst das Jahr mit den meisten Aufnahmen.
 * Ausnahme Kamera-Ordner: wird pro Jahr in ein eigenes Album "Kamera" aufgeteilt.
 * Ergebnis: Jahre absteigend, darin Alben nach neuestem Bild absteigend (Kamera zuerst).
 */
fun buildAlbums(images: List<MediaImage>): List<YearAlbums> {
    val albums = ArrayList<Album>()
    for ((path, imgs) in images.groupBy { it.relativePath }) {
        val first = imgs.first()
        if (first.isCamera) {
            for ((year, yImgs) in imgs.groupBy { yearOf(it.date) }) {
                albums += Album(path, "Kamera", yImgs, year, true)
            }
        } else {
            val year = yearFromPath(path)
                ?: imgs.groupingBy { yearOf(it.date) }.eachCount().maxByOrNull { it.value }?.key ?: 0
            albums += Album(path, first.folderName, imgs, year, false)
        }
    }
    return albums.groupBy { it.year }
        .map { (year, list) ->
            YearAlbums(
                year,
                list.sortedWith(compareByDescending<Album> { it.isCamera }.thenByDescending { it.images.first().date }),
            )
        }
        .sortedByDescending { it.year }
}

private val YEAR_PREFIX = Regex("""^((?:19|20)\d{2})(?!\d)""")

/**
 * Jahr aus dem Ordnerpfad, z.B. "Data/images/2015_Zinnen/" -> 2015 oder "Data/images/2025/" -> 2025.
 * Gesucht wird vom innersten Ordner nach aussen; der Ordnername muss mit dem Jahr beginnen.
 */
fun yearFromPath(relativePath: String): Int? =
    relativePath.trimEnd('/').split('/').asReversed()
        .firstNotNullOfOrNull { YEAR_PREFIX.find(it)?.groupValues?.get(1)?.toInt() }

private val DATE_PREFIX = Regex("""^((?:19|20)\d{2})(?:[-_. ]?(\d{2}))?(?:[-_. ]?(\d{2}))?(?!\d)""")

/** Jahr, Monat (0 = unbekannt), Tag (0 = unbekannt) aus einem Ordnernamen wie "2015_Zinnen", "2015-07_Nepal", "20150712 Ausflug". */
data class FolderDate(val year: Int, val month: Int, val day: Int)

fun folderDateFromPath(relativePath: String): FolderDate? {
    for (seg in relativePath.trimEnd('/').split('/').asReversed()) {
        val m = DATE_PREFIX.find(seg) ?: continue
        val year = m.groupValues[1].toInt()
        var month = m.groupValues[2].toIntOrNull() ?: 0
        var day = m.groupValues[3].toIntOrNull() ?: 0
        if (month !in 1..12) { month = 0; day = 0 }
        if (day !in 1..31) day = 0
        if (month > 0 && day > 0 && day > YearMonth.of(year, month).lengthOfMonth()) day = 0
        return FolderDate(year, month, day)
    }
    return null
}

/**
 * Datum für ein Bild ohne EXIF-Datum aus dem Ordnernamen.
 * Liegt ein vorhandenes Datum (DATE_TAKEN / Dateidatum) im passenden Jahr/Monat, wird dieses genauere genommen,
 * sonst der Anfang des Zeitraums (z.B. 1.1.2015 12:00).
 */
fun dateFromFolder(img: MediaImage): Long? {
    if (img.isCamera) return null
    val fd = folderDateFromPath(img.relativePath) ?: return null
    val zone = ZoneId.systemDefault()
    fun fits(ms: Long): Boolean {
        if (ms <= 0) return false
        val d = Instant.ofEpochMilli(ms).atZone(zone)
        return d.year == fd.year && (fd.month == 0 || d.monthValue == fd.month) && (fd.day == 0 || d.dayOfMonth == fd.day)
    }
    listOf(img.dateTaken, img.dateModified * 1000L, img.dateAdded).firstOrNull { fits(it) }?.let { return it }
    return LocalDate.of(fd.year, if (fd.month > 0) fd.month else 1, if (fd.day > 0) fd.day else 1)
        .atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
}

/** Gruppierung nach Land; nur Bilder mit GPS und gefundenem Land. */
fun buildCountries(images: List<MediaImage>, geo: Map<Long, GeoEntry>): List<CountryGroup> {
    val collator = Collator.getInstance()
    return images.groupBy { img ->
        val e = geo[img.id]
        if (e != null && e.dateModified == img.dateModified && e.country.isNotEmpty() && e.country != "-") e.country else null
    }
        .filterKeys { it != null }
        .map { (code, imgs) -> CountryGroup(code!!, Countries.name(code), Countries.flag(code), imgs) }
        .sortedWith { a, b -> collator.compare(a.name, b.name) }
}

/** Monats-Überschriften für die Bilderliste; Reihenfolge bleibt erhalten. */
fun monthGroups(images: List<MediaImage>): List<Pair<String, List<MediaImage>>> {
    val locale = Locale.getDefault()
    val fmt = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val zone = ZoneId.systemDefault()
    val out = ArrayList<Pair<String, List<MediaImage>>>()
    var currentKey = Int.MIN_VALUE
    var current = ArrayList<MediaImage>()
    var label = ""
    for (img in images) {
        val dt = Instant.ofEpochMilli(img.date).atZone(zone)
        val key = dt.year * 100 + dt.monthValue
        if (key != currentKey) {
            if (current.isNotEmpty()) out += label to current
            current = ArrayList()
            currentKey = key
            label = fmt.format(dt).replaceFirstChar { it.titlecase(locale) }
        }
        current += img
    }
    if (current.isNotEmpty()) out += label to current
    return out
}

object Countries {
    /** Landesname in Gerätesprache (z.B. "Portugal"); "#Name" für Gebiete ohne ISO-Code. */
    fun name(code: String): String {
        if (code.startsWith("#")) return code.drop(1)
        return try {
            val n = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
            if (n.isNullOrBlank() || n == code) code else n
        } catch (_: Exception) {
            code
        }
    }

    /** Flaggen-Emoji aus ISO-Code (Regional Indicator Symbols). */
    fun flag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🏳️"
        val base = 0x1F1E6
        return String(Character.toChars(base + (code[0] - 'A'))) + String(Character.toChars(base + (code[1] - 'A')))
    }
}
