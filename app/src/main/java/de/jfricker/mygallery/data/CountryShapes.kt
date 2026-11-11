package de.jfricker.mygallery.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * Ländergrenzen zum ZEICHNEN der Länderkarte (Natural Earth 1:50 Mio., gemeinfrei, ~1,6 MB).
 * assets/countries_map.geojson, Properties: id = ISO-Alpha-2 (wie CountryLookup, sonst "#Name"), name = deutscher Name.
 * Für die Zuordnung Bild -> Land wird weiterhin das genauere CountryLookup (1:10 Mio.) verwendet.
 */
class CountryShapes private constructor(val countries: List<Shape>) {

    /** Ein Land; polygons = Liste von Polygonen, jedes = Ringe [lon0, lat0, lon1, lat1, ...], erster Ring aussen. */
    class Shape(
        val code: String,
        val name: String,
        val polygons: List<List<DoubleArray>>,
        val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
    ) {
        fun contains(lat: Double, lon: Double): Boolean {
            if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) return false
            return polygons.any { rings -> inRing(rings[0], lat, lon) && (1 until rings.size).none { inRing(rings[it], lat, lon) } }
        }
    }

    val byCode: Map<String, Shape> = countries.associateBy { it.code }

    /** Land an einer Kartenposition (für Tipp auf die Karte). */
    fun at(lat: Double, lon: Double): Shape? = countries.firstOrNull { it.contains(lat, lon) }

    companion object {
        private const val ASSET = "countries_map.geojson"
        private val mutex = Mutex()
        @Volatile private var instance: CountryShapes? = null

        suspend fun get(context: Context): CountryShapes = mutex.withLock {
            instance ?: withContext(Dispatchers.IO) {
                context.applicationContext.assets.open(ASSET).use { CountryShapes(parse(JsonReader(InputStreamReader(it, Charsets.UTF_8)))) }
            }.also { instance = it }
        }

        private fun inRing(r: DoubleArray, lat: Double, lon: Double): Boolean {
            var inside = false
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = r[2 * i]; val yi = r[2 * i + 1]
                val xj = r[2 * j]; val yj = r[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        private fun parse(r: JsonReader): List<Shape> {
            val out = ArrayList<Shape>()
            r.use {
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

        private fun parseFeature(r: JsonReader): Shape? {
            var code: String? = null
            var name: String? = null
            var polygons: List<List<DoubleArray>> = emptyList()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "properties" -> {
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "id" -> code = r.nextString()
                            "name" -> name = r.nextString()
                            else -> r.skipValue()
                        }
                        r.endObject()
                    }
                    "geometry" -> {
                        if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "coordinates" -> polygons = readCoordinates(r)
                            else -> r.skipValue()
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (code == null || polygons.isEmpty()) return null
            var minLon = Double.MAX_VALUE; var minLat = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            for (p in polygons) {
                val ring = p[0]
                var i = 0
                while (i < ring.size) {
                    if (ring[i] < minLon) minLon = ring[i]
                    if (ring[i] > maxLon) maxLon = ring[i]
                    if (ring[i + 1] < minLat) minLat = ring[i + 1]
                    if (ring[i + 1] > maxLat) maxLat = ring[i + 1]
                    i += 2
                }
            }
            return Shape(code!!, name ?: code!!, polygons, minLon, minLat, maxLon, maxLat)
        }

        /**
         * Liest "coordinates" als MultiPolygon (Polygon -> Ringe -> Punkte).
         * Ein einfaches Polygon (eine Ebene weniger) wird erkannt und als ein Polygon zurückgegeben.
         */
        private fun readCoordinates(r: JsonReader): List<List<DoubleArray>> {
            val tree = readNested(r)
            return when (depth(tree)) {
                3 -> listOf((tree as List<*>).map { ringOf(it) })                  // Polygon
                4 -> (tree as List<*>).map { poly -> (poly as List<*>).map { ringOf(it) } } // MultiPolygon
                else -> emptyList()
            }.filter { it.isNotEmpty() && it[0].size >= 6 }
        }

        private fun readNested(r: JsonReader): Any {
            return if (r.peek() == JsonToken.BEGIN_ARRAY) {
                r.beginArray()
                val list = ArrayList<Any>()
                while (r.hasNext()) list += readNested(r)
                r.endArray()
                list
            } else r.nextDouble()
        }

        private fun depth(x: Any?): Int = if (x is List<*>) 1 + depth(x.firstOrNull()) else 0

        private fun ringOf(x: Any?): DoubleArray {
            val pts = x as List<*>
            val out = DoubleArray(pts.size * 2)
            pts.forEachIndexed { i, p ->
                val xy = p as List<*>
                out[2 * i] = xy[0] as Double
                out[2 * i + 1] = xy[1] as Double
            }
            return out
        }
    }
}
