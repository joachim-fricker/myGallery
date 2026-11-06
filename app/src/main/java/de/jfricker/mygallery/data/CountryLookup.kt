package de.jfricker.mygallery.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Offline-Zuordnung GPS -> Land über Ländergrenzen (Natural Earth, 1:10 Mio., Public Domain).
 * Die 1:10-Mio.-Grenzen sind nötig, damit z.B. das Everest Base Camp (Nepal) nicht in China landet.
 *
 * Quelle der Grenzen (in dieser Reihenfolge):
 *  1. assets/countries_10m.geojson (falls du die Datei ins Projekt legst -> komplett offline)
 *  2. bereits heruntergeladene Datei im App-Speicher
 *  3. einmaliger Download von GitHub, danach offline
 */
class CountryLookup private constructor(private val countries: List<CountryShape>) {

    class CountryShape(
        val code: String,
        val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
        /** Ringe als [lon0, lat0, lon1, lat1, ...] */
        val rings: List<DoubleArray>,
    ) {
        fun containsBox(lon: Double, lat: Double, margin: Double = 0.0) =
            lon >= minLon - margin && lon <= maxLon + margin && lat >= minLat - margin && lat <= maxLat + margin

        /** Even-odd Regel über alle Ringe (Löcher werden so automatisch ausgeschlossen). */
        fun contains(x: Double, y: Double): Boolean {
            var inside = false
            for (r in rings) {
                var j = r.size - 2
                var i = 0
                while (i < r.size) {
                    val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                    i += 2
                }
            }
            return inside
        }

        /** Ungefährer Abstand in Grad zur Grenze. */
        fun distance(x: Double, y: Double): Double {
            val k = cos(Math.toRadians(y))
            var best = Double.MAX_VALUE
            for (r in rings) {
                var i = 0
                while (i + 3 < r.size) {
                    val ax = r[i] * k; val ay = r[i + 1]; val bx = r[i + 2] * k; val by = r[i + 3]
                    val px = x * k
                    val dx = bx - ax; val dy = by - ay
                    val len = dx * dx + dy * dy
                    var t = if (len == 0.0) 0.0 else ((px - ax) * dx + (y - ay) * dy) / len
                    t = t.coerceIn(0.0, 1.0)
                    val cx = ax + t * dx - px; val cy = ay + t * dy - y
                    val d = cx * cx + cy * cy
                    if (d < best) best = d
                    i += 2
                }
            }
            return sqrt(best)
        }
    }

    /** Liefert ISO-Code (z.B. "PT") oder null. Punkte knapp ausserhalb (Küste/Meer) gehen zum nächsten Land. */
    fun find(lat: Double, lon: Double): String? {
        for (c in countries) {
            if (c.containsBox(lon, lat) && c.contains(lon, lat)) return c.code
        }
        var best: String? = null
        var bestD = NEAREST_MAX_DEG
        for (c in countries) {
            if (!c.containsBox(lon, lat, NEAREST_MAX_DEG)) continue
            val d = c.distance(lon, lat)
            if (d < bestD) {
                bestD = d; best = c.code
            }
        }
        return best
    }

    companion object {
        private const val NEAREST_MAX_DEG = 0.3 // ca. 30 km
        private const val ASSET = "countries_10m.geojson"
        private const val OLD_FILE = "countries.geojson" // alte 1:50-Mio.-Datei
        /** Erhöhen, wenn sich die Grenzdaten ändern -> alle Länder werden neu berechnet. */
        const val DATA_VERSION = 2
        const val DOWNLOAD_URL =
            "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson"

        private val mutex = Mutex()
        @Volatile private var instance: CountryLookup? = null

        suspend fun get(context: Context): CountryLookup? = mutex.withLock {
            instance ?: withContext(Dispatchers.IO) { load(context.applicationContext) }?.also { instance = it }
        }

        private fun load(context: Context): CountryLookup? {
            // 1. Asset
            try {
                context.assets.open(ASSET).use { return CountryLookup(parse(it)) }
            } catch (_: Exception) {
            }
            // 2./3. Datei im App-Speicher
            File(context.filesDir, OLD_FILE).delete()
            val file = File(context.filesDir, ASSET)
            if (!file.exists() || file.length() < 1000) {
                if (!download(file)) return null
            }
            return try {
                file.inputStream().buffered().use { CountryLookup(parse(it)) }
            } catch (_: Exception) {
                file.delete(); null
            }
        }

        private fun download(target: File): Boolean = try {
            val tmp = File(target.path + ".part")
            val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            tmp.renameTo(target)
        } catch (_: Exception) {
            false
        }

        // ---------- GeoJSON Streaming-Parser ----------

        private fun parse(input: InputStream): List<CountryShape> {
            val out = ArrayList<CountryShape>()
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "features") {
                        r.beginArray()
                        while (r.hasNext()) parseFeature(r)?.let { out += it }
                        r.endArray()
                    } else r.skipValue()
                }
                r.endObject()
            }
            return out
        }

        private fun parseFeature(r: JsonReader): CountryShape? {
            val props = HashMap<String, String>()
            val rings = ArrayList<DoubleArray>()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "properties" -> readProps(r, props)
                    "geometry" -> readGeometry(r, rings)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (rings.isEmpty()) return null

            val code = listOf(props["ISO_A2_EH"], props["ISO_A2"], props["WB_A2"])
                .firstOrNull { it != null && it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
                ?: ("#" + (props["NAME_DE"] ?: props["NAME"] ?: return null))

            var minLon = Double.MAX_VALUE; var minLat = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            for (ring in rings) {
                var i = 0
                while (i < ring.size) {
                    if (ring[i] < minLon) minLon = ring[i]
                    if (ring[i] > maxLon) maxLon = ring[i]
                    if (ring[i + 1] < minLat) minLat = ring[i + 1]
                    if (ring[i + 1] > maxLat) maxLat = ring[i + 1]
                    i += 2
                }
            }
            return CountryShape(code, minLon, minLat, maxLon, maxLat, rings)
        }

        private fun readProps(r: JsonReader, props: MutableMap<String, String>) {
            if (r.peek() == JsonToken.NULL) { r.nextNull(); return }
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName().uppercase()
                when (r.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> props[key] = r.nextString()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        private fun readGeometry(r: JsonReader, rings: MutableList<DoubleArray>) {
            if (r.peek() == JsonToken.NULL) { r.nextNull(); return }
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "coordinates") readCoords(r, rings) else r.skipValue()
            }
            r.endObject()
        }

        private fun readCoords(r: JsonReader, rings: MutableList<DoubleArray>) {
            if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
            r.beginArray()
            readArrayBody(r, rings)
        }

        /**
         * Liest den Rest eines bereits geöffneten Arrays (beliebige Tiefe, Polygon oder MultiPolygon).
         * Ein Array, dessen Elemente Positionen [lon, lat] sind, ist ein Ring.
         */
        private fun readArrayBody(r: JsonReader, rings: MutableList<DoubleArray>) {
            val ring = DoubleList()
            while (r.hasNext()) {
                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray()
                    if (r.hasNext() && r.peek() == JsonToken.NUMBER) {
                        ring.add(r.nextDouble())
                        ring.add(r.nextDouble())
                        while (r.hasNext()) r.skipValue()
                        r.endArray()
                    } else {
                        readArrayBody(r, rings)
                    }
                } else {
                    r.skipValue()
                }
            }
            r.endArray()
            if (ring.size >= 6) rings += ring.toArray()
        }
    }
}

/** Wachsendes DoubleArray ohne Boxing (die 1:10-Mio.-Daten haben über eine Million Punkte). */
private class DoubleList {
    private var data = DoubleArray(64)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): DoubleArray = data.copyOf(size)
}
