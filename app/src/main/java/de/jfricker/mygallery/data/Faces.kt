package de.jfricker.mygallery.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.util.LruCache
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Personen-Erkennung, komplett auf dem Gerät (Version 2):
 *  1. ML Kit findet Gesichter + 5 Merkmalspunkte (Augen, Nase, Mundwinkel)
 *  2. 5-Punkt-Ausrichtung auf die Standard-Vorlage (wie beim Training der Gesichtsmodelle)
 *  3. Gesichtsmodell (InsightFace w600k_mbf als ONNX, oder MobileFaceNet als TFLite) -> Vektor
 *  4. Zuordnung über BESTÄTIGTE Beispiele jeder benannten Person (k-nächste-Nachbarn):
 *       sicher   -> automatisch (AUTO)
 *       unsicher -> Vorschlag (SUGGESTED), der Benutzer bestätigt/lehnt ab
 *       sonst    -> unbenannte Gruppe (UNNAMED), damit neue Personen entdeckt werden
 */

enum class FaceStatus(val code: Int) {
    UNNAMED(0), AUTO(1), SUGGESTED(2), CONFIRMED(3);

    companion object {
        fun of(c: Int) = entries.firstOrNull { it.code == c } ?: UNNAMED
    }
}

/** Schwellen je Modell (Kosinus-Ähnlichkeit). */
enum class ModelKind(
    val label: String,
    /** ab hier automatisch zuordnen */
    val autoT: Float,
    /** ab hier als Vorschlag zeigen */
    val suggestT: Float,
    /** unbenannte Gesichter zu einer Gruppe zusammenfassen */
    val clusterT: Float,
    /** Mindestabstand zur zweitbesten Person für automatisch */
    val margin: Float,
) {
    INSIGHTFACE("InsightFace w600k_mbf", 0.45f, 0.28f, 0.45f, 0.08f),
    MOBILEFACENET("MobileFaceNet", 0.70f, 0.55f, 0.62f, 0.06f),
}

/** Gesicht ohne Vektor – für die UI. Box relativ (0..1) zum Bild. */
data class FaceRef(
    val id: Long,
    val imageId: Long,
    val personId: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val status: FaceStatus = FaceStatus.UNNAMED,
    val score: Float = 0f,
) {
    val area: Float get() = (right - left) * (bottom - top)
}

/** Eine Personen-Gruppe (z.B. "Haupt", "VB"). */
data class GroupDef(val id: Int, val name: String)

data class FaceSnapshot(
    val names: Map<Int, String> = emptyMap(),
    /** Benutzerdefinierte Reihenfolge der benannten Personen (kleiner = weiter oben) */
    val orders: Map<Int, Int> = emptyMap(),
    /** Personen-Gruppen in Anzeige-Reihenfolge (erste = "Haupt", immer sichtbar) */
    val groups: List<GroupDef> = emptyList(),
    /** Gruppe jeder benannten Person: personId -> groupId */
    val groupOf: Map<Int, Int> = emptyMap(),
    /** Vom Benutzer gewähltes Titelbild: personId -> faceId */
    val covers: Map<Int, Long> = emptyMap(),
    val facesByImage: Map<Long, List<FaceRef>> = emptyMap(),
    val modelMissing: Boolean = false,
    val modelLabel: String = "",
)

class DetectedFace(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val embedding: FloatArray,
    /** Schärfe des ausgerichteten Gesichts (Laplace-Varianz) */
    val sharpness: Float,
)

private class FaceRecord(
    val id: Long,
    val imageId: Long,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    var personId: Int,
    var status: FaceStatus,
    var score: Float,
    val sharpness: Float,
    val embedding: FloatArray,
)

/** Zuordnung aus einem früheren Scan, die beim Neu-Scan übernommen wird (gleiches Bild, gleiche Stelle). */
private class LegacyFace(val left: Float, val top: Float, val right: Float, val bottom: Float, val personId: Int, val confirmed: Boolean)

private fun iou(aL: Float, aT: Float, aR: Float, aB: Float, bL: Float, bT: Float, bR: Float, bB: Float): Float {
    val iw = min(aR, bR) - max(aL, bL)
    val ih = min(aB, bB) - max(aT, bT)
    if (iw <= 0f || ih <= 0f) return 0f
    val inter = iw * ih
    val union = (aR - aL) * (aB - aT) + (bR - bL) * (bB - bT) - inter
    return if (union <= 0f) 0f else inter / union
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return -1f
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun normalized(v: FloatArray): FloatArray {
    var n = 0f
    for (x in v) n += x * x
    n = sqrt(n)
    return if (n > 0f) FloatArray(v.size) { v[it] / n } else v
}

/** Persistente Gesichter-Datenbank (Dateien im App-Speicher). */
class FaceDb(private val dir: File) {

    companion object {
        private const val VERSION = 2
        /** Unschärfere Gesichter werden nie automatisch zugeordnet (nur Vorschlag) */
        const val SHARPNESS_MIN = 35f
        /** Max. Beispiele pro Person für den Vergleich */
        private const val MAX_ANCHORS = 80
        /** Gruppe "Haupt": immer sichtbar, Standard für neu benannte Personen */
        const val MAIN_GROUP = 1
        private val DEFAULT_GROUPS = listOf("Haupt", "Alcina Familie", "Ulli", "VB", "andere")
    }

    var kind = ModelKind.MOBILEFACENET
        private set
    var modelId: String = ""
        private set

    private val faces = ArrayList<FaceRecord>()
    private val done = HashMap<Long, Long>()
    private val names = HashMap<Int, String>()
    private val orders = HashMap<Int, Int>()
    /** Gruppen in Anzeige-Reihenfolge */
    private val groups = ArrayList<GroupDef>()
    /** personId -> groupId */
    private val groupOf = HashMap<Int, Int>()
    private val covers = HashMap<Int, Long>()
    /** faceId -> Personen, zu denen dieses Gesicht NICHT gehört (vom Benutzer abgelehnt) */
    private val blocked = HashMap<Long, MutableSet<Int>>()
    /** imageId -> Zuordnungen aus dem früheren Scan */
    private val migration = HashMap<Long, MutableList<LegacyFace>>()
    // Summen der unbenannten Gruppen (für das Zusammenfassen neuer Gesichter)
    private val sums = HashMap<Int, FloatArray>()
    private var nextFaceId = 1L
    private var nextPersonId = 1

    private val facesFile get() = File(dir, "faces.bin")
    private val doneFile get() = File(dir, "faces_done.tsv")
    private val namesFile get() = File(dir, "persons.tsv")
    private val blockedFile get() = File(dir, "faces_blocked.tsv")
    private val coversFile get() = File(dir, "persons_cover.tsv")
    private val migrationFile get() = File(dir, "migration.tsv")
    private val modelFile get() = File(dir, "model.txt")
    private val groupsFile get() = File(dir, "groups.tsv")

    fun isDone(img: MediaImage) = done[img.id] == img.dateModified

    // ------------------------------------------------------------------ Laden / Speichern

    fun load() {
        faces.clear(); done.clear(); names.clear(); orders.clear(); groups.clear(); groupOf.clear(); covers.clear()
        val legacyOthers = HashSet<Int>()
        blocked.clear(); migration.clear()
        var legacy = false
        try {
            if (modelFile.exists()) {
                val p = modelFile.readText().trim().split('\t')
                modelId = p.getOrNull(0) ?: ""
                kind = ModelKind.entries.firstOrNull { it.name == p.getOrNull(1) } ?: ModelKind.MOBILEFACENET
            }
            if (namesFile.exists()) namesFile.forEachLine { line ->
                // Format: id \t name [\t reihenfolge \t other(1/0)]
                val q = line.split('\t')
                val id = q.getOrNull(0)?.toIntOrNull() ?: return@forEachLine
                val name = q.getOrNull(1)
                if (!name.isNullOrBlank()) {
                    names[id] = name
                    q.getOrNull(2)?.toIntOrNull()?.let { orders[id] = it }
                    if (q.getOrNull(3) == "1") legacyOthers += id
                    q.getOrNull(4)?.toIntOrNull()?.let { groupOf[id] = it }
                }
            }
            if (groupsFile.exists()) groupsFile.forEachLine { line ->
                val p = line.split('\t', limit = 2)
                val gid = p.getOrNull(0)?.toIntOrNull() ?: return@forEachLine
                val gname = p.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@forEachLine
                groups += GroupDef(gid, gname)
            }
            if (coversFile.exists()) coversFile.forEachLine { line ->
                val p = line.split('\t')
                val pid = p.getOrNull(0)?.toIntOrNull() ?: return@forEachLine
                covers[pid] = p.getOrNull(1)?.toLongOrNull() ?: return@forEachLine
            }
            if (blockedFile.exists()) blockedFile.forEachLine { line ->
                val p = line.split('\t')
                val fid = p.getOrNull(0)?.toLongOrNull() ?: return@forEachLine
                val pid = p.getOrNull(1)?.toIntOrNull() ?: return@forEachLine
                blocked.getOrPut(fid) { HashSet() }.add(pid)
            }
            if (migrationFile.exists()) migrationFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size < 7) return@forEachLine
                val img = p[0].toLongOrNull() ?: return@forEachLine
                migration.getOrPut(img) { ArrayList() } += LegacyFace(
                    p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toInt(), p[6] == "1",
                )
            }
            if (doneFile.exists()) doneFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 2) done[p[0].toLongOrNull() ?: return@forEachLine] = p[1].toLongOrNull() ?: 0L
            }
            if (facesFile.exists()) DataInputStream(facesFile.inputStream().buffered()).use { inp ->
                when (inp.readInt()) {
                    VERSION -> {
                        repeat(inp.readInt()) {
                            val id = inp.readLong(); val imageId = inp.readLong()
                            val l = inp.readFloat(); val t = inp.readFloat(); val r = inp.readFloat(); val b = inp.readFloat()
                            val pid = inp.readInt()
                            val status = FaceStatus.of(inp.readInt())
                            val score = inp.readFloat()
                            val sharp = inp.readFloat()
                            val e = FloatArray(inp.readInt()) { inp.readFloat() }
                            faces += FaceRecord(id, imageId, l, t, r, b, pid, status, score, sharp, e)
                        }
                    }
                    1 -> {
                        // Alte Version: Zuordnungen lesen und für den Neu-Scan übernehmen
                        legacy = true
                        repeat(inp.readInt()) {
                            val id = inp.readLong(); val imageId = inp.readLong()
                            val l = inp.readFloat(); val t = inp.readFloat(); val r = inp.readFloat(); val b = inp.readFloat()
                            val pid = inp.readInt()
                            val e = FloatArray(inp.readInt()) { inp.readFloat() }
                            faces += FaceRecord(id, imageId, l, t, r, b, pid, FaceStatus.AUTO, 0f, 100f, e)
                        }
                    }
                    else -> { faces.clear(); done.clear() }
                }
            } else if (done.isNotEmpty() && faces.isEmpty()) {
                done.clear()
            }
        } catch (_: Exception) {
            faces.clear(); done.clear()
        }
        if (groups.isEmpty()) {
            DEFAULT_GROUPS.forEachIndexed { i, n -> groups += GroupDef(i + 1, n) }
            val andere = groups.first { it.name == "andere" }.id
            for (pid in names.keys) if (pid !in groupOf) groupOf[pid] = if (pid in legacyOthers) andere else MAIN_GROUP
        }
        if (groups.none { it.id == MAIN_GROUP }) groups.add(0, GroupDef(MAIN_GROUP, "Haupt"))
        for (pid in names.keys) if (groupOf[pid]?.let { g -> groups.any { it.id == g } } != true) groupOf[pid] = MAIN_GROUP
        nextFaceId = (faces.maxOfOrNull { it.id } ?: 0L) + 1
        nextPersonId = max(faces.maxOfOrNull { it.personId } ?: 0, names.keys.maxOrNull() ?: 0) + 1
        migration.values.flatten().maxOfOrNull { it.personId }?.let { nextPersonId = max(nextPersonId, it + 1) }
        if (legacy) prepareRescan("", kind) else rebuildSums()
    }

    fun save() {
        try {
            dir.mkdirs()
            val tmp = File(dir, "faces.bin.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeInt(faces.size)
                for (f in faces) {
                    out.writeLong(f.id); out.writeLong(f.imageId)
                    out.writeFloat(f.left); out.writeFloat(f.top); out.writeFloat(f.right); out.writeFloat(f.bottom)
                    out.writeInt(f.personId)
                    out.writeInt(f.status.code)
                    out.writeFloat(f.score)
                    out.writeFloat(f.sharpness)
                    out.writeInt(f.embedding.size)
                    for (v in f.embedding) out.writeFloat(v)
                }
            }
            if (!tmp.renameTo(facesFile)) { facesFile.delete(); tmp.renameTo(facesFile) }
            doneFile.bufferedWriter().use { w -> for ((k, v) in done) w.write("$k\t$v\n") }
            namesFile.bufferedWriter().use { w ->
                for ((k, v) in names) {
                    val clean = v.replace('\n', ' ').replace('\t', ' ')
                    w.write("$k\t$clean\t${orders[k] ?: ""}\t0\t${groupOf[k] ?: MAIN_GROUP}\n")
                }
            }
            blockedFile.bufferedWriter().use { w -> for ((f, ps) in blocked) for (p in ps) w.write("$f\t$p\n") }
            coversFile.bufferedWriter().use { w -> for ((p, f) in covers) w.write("$p\t$f\n") }
            groupsFile.bufferedWriter().use { w -> for (g in groups) w.write("${g.id}\t${g.name.replace('\n', ' ')}\n") }
            if (migration.isEmpty()) migrationFile.delete()
            else migrationFile.bufferedWriter().use { w ->
                for ((img, list) in migration) for (m in list) {
                    w.write("$img\t${m.left}\t${m.top}\t${m.right}\t${m.bottom}\t${m.personId}\t${if (m.confirmed) 1 else 0}\n")
                }
            }
            modelFile.writeText("$modelId\t${kind.name}")
        } catch (_: Exception) {
        }
    }

    /**
     * Neues Modell / neue Ausrichtung: alle Gesichter verwerfen und neu scannen.
     * Namen, Reihenfolge und Gruppen bleiben; die bisherigen Zuordnungen benannter Personen werden
     * gemerkt und beim Neu-Scan auf die neu gefundenen Gesichter an derselben Stelle übertragen.
     */
    fun prepareRescan(newModelId: String, newKind: ModelKind) {
        for (f in faces) {
            if (f.personId !in names || f.status == FaceStatus.SUGGESTED) continue
            val confirmed = f.status == FaceStatus.CONFIRMED || covers[f.personId] == f.id
            migration.getOrPut(f.imageId) { ArrayList() } += LegacyFace(f.left, f.top, f.right, f.bottom, f.personId, confirmed)
        }
        faces.clear(); done.clear(); blocked.clear(); covers.clear(); sums.clear()
        nextFaceId = 1L
        modelId = newModelId
        kind = newKind
    }

    /** Gelöschte oder geänderte Bilder entfernen. */
    fun prune(images: List<MediaImage>) {
        val mod = images.associate { it.id to it.dateModified }
        migration.keys.retainAll(mod.keys)
        val stale = done.filter { (id, dm) -> mod[id] != dm }.keys.toHashSet()
        if (stale.isEmpty()) return
        stale.forEach { done.remove(it) }
        faces.removeAll { it.imageId in stale }
        val alive = faces.mapTo(HashSet()) { it.id }
        blocked.keys.retainAll(alive)
        rebuildSums()
    }

    // ------------------------------------------------------------------ Zuordnung

    private class Anchors(val pid: Int, val vecs: List<FloatArray>, val centroid: FloatArray)

    private var anchors: List<Anchors> = emptyList()

    /** Beispiele jeder benannten Person: bestätigte Gesichter, bei wenigen zusätzlich die sichersten automatischen. */
    fun refreshAnchors() {
        val byPerson = faces.filter { it.personId in names }.groupBy { it.personId }
        anchors = byPerson.mapNotNull { (pid, list) ->
            val confirmed = list.filter { it.status == FaceStatus.CONFIRMED }.sortedByDescending { it.id }.take(MAX_ANCHORS)
            val chosen = if (confirmed.size >= 5) confirmed else confirmed +
                list.filter { it.status == FaceStatus.AUTO }.sortedByDescending { it.score }.take(MAX_ANCHORS - confirmed.size)
            if (chosen.isEmpty()) return@mapNotNull null
            val vecs = chosen.map { it.embedding }
            val dim = vecs.first().size
            val sum = FloatArray(dim)
            for (v in vecs) if (v.size == dim) for (i in 0 until dim) sum[i] += v[i]
            Anchors(pid, vecs, normalized(sum))
        }
    }

    /** Beste benannte Person: (personId, score, score der zweitbesten). */
    private fun bestNamed(f: FaceRecord): Triple<Int, Float, Float>? {
        if (anchors.isEmpty()) return null
        val notAllowed = blocked[f.id]
        val candidates = anchors.filter { notAllowed?.contains(it.pid) != true }
            .sortedByDescending { dot(it.centroid, f.embedding) }
            .take(3)
        val scored = candidates.map { a ->
            val sims = a.vecs.asSequence().filter { it !== f.embedding }.map { dot(it, f.embedding) }
                .sortedDescending().take(3).toList()
            a.pid to (if (sims.isEmpty()) -1f else sims.average().toFloat())
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0f
        return Triple(best.first, best.second, second)
    }

    /** Gesicht einordnen: automatisch / Vorschlag / unbenannte Gruppe. */
    private fun classify(f: FaceRecord) {
        val r = bestNamed(f)
        if (r != null) {
            val (pid, score, second) = r
            if (score >= kind.autoT && score - second >= kind.margin && f.sharpness >= SHARPNESS_MIN) {
                f.personId = pid; f.status = FaceStatus.AUTO; f.score = score; return
            }
            if (score >= kind.suggestT) {
                f.personId = pid; f.status = FaceStatus.SUGGESTED; f.score = score; return
            }
        }
        toUnnamed(f)
    }

    private fun toUnnamed(f: FaceRecord) {
        f.status = FaceStatus.UNNAMED
        f.score = 0f
        f.personId = clusterAssign(f)
        addToSum(f.personId, f.embedding)
    }

    /** Unbenannte Gruppe mit ähnlichstem Durchschnitt, sonst neue Gruppe. */
    private fun clusterAssign(f: FaceRecord): Int {
        val notAllowed = blocked[f.id]
        var best = -1
        var bestSim = kind.clusterT
        for ((pid, sum) in sums) {
            if (pid in names || notAllowed?.contains(pid) == true || sum.size != f.embedding.size) continue
            var d = 0f; var n = 0f
            for (i in sum.indices) { d += sum[i] * f.embedding[i]; n += sum[i] * sum[i] }
            if (n <= 0f) continue
            val sim = d / sqrt(n)
            if (sim > bestSim) { bestSim = sim; best = pid }
        }
        return if (best >= 0) best else nextPersonId++
    }

    fun addImage(img: MediaImage, detected: List<DetectedFace>) {
        faces.removeAll { it.imageId == img.id }
        val legacy = migration.remove(img.id)
        for (d in detected) {
            val rec = FaceRecord(
                nextFaceId++, img.id, d.left, d.top, d.right, d.bottom,
                -1, FaceStatus.UNNAMED, 0f, d.sharpness, d.embedding,
            )
            // Zuordnung aus dem früheren Scan übernehmen?
            val match = legacy?.maxByOrNull { iou(it.left, it.top, it.right, it.bottom, rec.left, rec.top, rec.right, rec.bottom) }
            if (match != null && match.personId in names &&
                iou(match.left, match.top, match.right, match.bottom, rec.left, rec.top, rec.right, rec.bottom) > 0.35f
            ) {
                legacy.remove(match)
                rec.personId = match.personId
                rec.status = if (match.confirmed) FaceStatus.CONFIRMED else FaceStatus.AUTO
                rec.score = 1f
                if (match.confirmed && covers[match.personId] == null) covers[match.personId] = rec.id
            } else {
                classify(rec)
            }
            faces += rec
        }
        done[img.id] = img.dateModified
    }

    /** Alle nicht bestätigten Gesichter neu einordnen (nach Bestätigungen, Namen, Zusammenführen). */
    fun reclassifyAll() {
        refreshAnchors()
        rebuildSums()
        for (f in faces) {
            when (f.status) {
                FaceStatus.CONFIRMED -> Unit
                FaceStatus.AUTO, FaceStatus.SUGGESTED -> classify(f)
                FaceStatus.UNNAMED -> {
                    val r = bestNamed(f) ?: continue
                    val (pid, score, second) = r
                    if (score >= kind.autoT && score - second >= kind.margin && f.sharpness >= SHARPNESS_MIN) {
                        f.personId = pid; f.status = FaceStatus.AUTO; f.score = score
                    } else if (score >= kind.suggestT) {
                        f.personId = pid; f.status = FaceStatus.SUGGESTED; f.score = score
                    }
                }
            }
        }
        rebuildSums()
    }

    // ------------------------------------------------------------------ Aktionen des Benutzers

    private fun face(id: Long) = faces.firstOrNull { it.id == id }

    /** Gesichter gehören sicher zu [personId] (benannt -> bestätigt, unbenannt -> Gruppenmitglied). */
    fun confirm(faceIds: List<Long>, personId: Int) {
        for (id in faceIds) {
            val f = face(id) ?: continue
            if (f.personId >= 0 && f.personId != personId) {
                blocked.getOrPut(f.id) { HashSet() }.add(f.personId)
                if (covers[f.personId] == f.id) covers.remove(f.personId)
            }
            blocked[f.id]?.remove(personId)
            f.personId = personId
            f.status = if (personId in names) FaceStatus.CONFIRMED else FaceStatus.UNNAMED
            f.score = 1f
        }
        rebuildSums()
    }

    /** Gesichter gehören NICHT zu ihrer aktuellen Person. */
    fun reject(faceIds: List<Long>) {
        for (id in faceIds) {
            val f = face(id) ?: continue
            if (f.personId >= 0) {
                blocked.getOrPut(f.id) { HashSet() }.add(f.personId)
                if (covers[f.personId] == f.id) covers.remove(f.personId)
            }
            rebuildSums()
            toUnnamed(f)
        }
        rebuildSums()
    }

    fun rename(personId: Int, name: String) {
        val wasNamed = personId in names
        if (name.isBlank()) {
            names.remove(personId); orders.remove(personId); groupOf.remove(personId)
            for (f in faces) if (f.personId == personId) { f.status = FaceStatus.UNNAMED; f.score = 0f }
        } else {
            names[personId] = name.trim()
            if (personId !in orders) orders[personId] = (orders.values.maxOrNull() ?: -1) + 1
            if (personId !in groupOf) groupOf[personId] = MAIN_GROUP
            if (!wasNamed) {
                // Gruppe bekommt einen Namen: Gesichter gelten als automatisch, das Titelbild als bestätigt
                val own = faces.filter { it.personId == personId }
                val coverId = covers[personId] ?: own.maxByOrNull { it.right - it.left }?.id
                for (f in own) {
                    f.status = if (f.id == coverId) FaceStatus.CONFIRMED else FaceStatus.AUTO
                    f.score = 1f
                }
            }
        }
        rebuildSums()
    }

    /** Neue (leere) Person anlegen; mit Name, falls angegeben. Liefert die neue ID. */
    fun createPerson(name: String): Int {
        val pid = nextPersonId++
        if (name.isNotBlank()) rename(pid, name)
        return pid
    }

    /** Person einer Gruppe zuordnen. */
    fun setGroup(personId: Int, groupId: Int) {
        if (names.containsKey(personId) && groups.any { it.id == groupId }) groupOf[personId] = groupId
    }

    /** Neue Gruppe am Ende anlegen; liefert die ID. */
    fun createGroup(name: String): Int {
        val id = (groups.maxOfOrNull { it.id } ?: 0) + 1
        groups += GroupDef(id, name.trim())
        return id
    }

    fun renameGroup(groupId: Int, name: String) {
        val i = groups.indexOfFirst { it.id == groupId }
        if (i >= 0 && name.isNotBlank()) groups[i] = GroupDef(groupId, name.trim())
    }

    /**
     * Ergebnis aus "Gruppen bearbeiten" übernehmen: Reihenfolge = Listenreihenfolge,
     * id <= 0 = neue Gruppe, fehlende Gruppen werden gelöscht (Personen -> "Haupt").
     */
    fun applyGroups(edited: List<GroupDef>) {
        val keep = edited.filter { it.id > 0 }.map { it.id }.toSet()
        for (g in groups.toList()) if (g.id !in keep && g.id != MAIN_GROUP) deleteGroup(g.id)
        val result = ArrayList<GroupDef>()
        for (e in edited) {
            val name = e.name.trim()
            if (name.isEmpty()) continue
            if (e.id > 0) {
                if (groups.any { it.id == e.id }) result += GroupDef(e.id, name)
            } else {
                val id = max((groups.maxOfOrNull { it.id } ?: 0), (result.maxOfOrNull { it.id } ?: 0)) + 1
                result += GroupDef(id, name)
            }
        }
        // "Haupt" darf nicht verschwinden
        if (result.none { it.id == MAIN_GROUP }) result.add(0, groups.firstOrNull { it.id == MAIN_GROUP } ?: GroupDef(MAIN_GROUP, "Haupt"))
        groups.clear()
        groups.addAll(result)
    }

    /** Gruppe löschen (nicht "Haupt"); ihre Personen kommen nach "Haupt". */
    fun deleteGroup(groupId: Int) {
        if (groupId == MAIN_GROUP) return
        groups.removeAll { it.id == groupId }
        for ((pid, g) in groupOf.entries.toList()) if (g == groupId) groupOf[pid] = MAIN_GROUP
    }

    /** Neue Reihenfolge der benannten Personen (Drag & Drop). */
    fun setOrder(ids: List<Int>) {
        ids.forEachIndexed { i, id -> orders[id] = i }
    }

    /** Titelbild festlegen (gilt bei benannten Personen auch als Bestätigung). */
    fun setCover(personId: Int, faceId: Long) {
        covers[personId] = faceId
        if (personId in names) face(faceId)?.let { if (it.personId == personId) it.status = FaceStatus.CONFIRMED }
    }

    private fun transferMeta(from: Int, into: Int) {
        if (names[into] == null) {
            names[from]?.let { names[into] = it }
            orders[from]?.let { orders[into] = it }
            groupOf[from]?.let { groupOf[into] = it }
        }
        if (covers[into] == null) covers[from]?.let { covers[into] = it }
        names.remove(from); orders.remove(from); groupOf.remove(from); covers.remove(from)
    }

    /** Alle Gesichter von [from] zu [into] verschieben. */
    fun merge(from: Int, into: Int) {
        if (from == into) return
        for (f in faces) if (f.personId == from) f.personId = into
        transferMeta(from, into)
        if (into in names) for (f in faces) if (f.personId == into && f.status == FaceStatus.UNNAMED) {
            f.status = FaceStatus.AUTO; f.score = 1f
        }
        rebuildSums()
    }

    fun snapshot(modelMissing: Boolean) = FaceSnapshot(
        names = HashMap(names),
        orders = HashMap(orders),
        groups = ArrayList(groups),
        groupOf = HashMap(groupOf),
        covers = HashMap(covers),
        facesByImage = faces.groupBy({ it.imageId }) {
            FaceRef(it.id, it.imageId, it.personId, it.left, it.top, it.right, it.bottom, it.status, it.score)
        },
        modelMissing = modelMissing,
        modelLabel = kind.label,
    )

    private fun addToSum(pid: Int, e: FloatArray) {
        if (pid < 0 || pid in names) return
        val s = sums.getOrPut(pid) { FloatArray(e.size) }
        if (s.size == e.size) for (i in s.indices) s[i] += e[i]
    }

    private fun rebuildSums() {
        sums.clear()
        for (f in faces) if (f.status == FaceStatus.UNNAMED) addToSum(f.personId, f.embedding)
    }
}

// ====================================================================== Gesichtsmodelle

/** Wandelt ein ausgerichtetes Gesichtsbild in einen normierten Vektor um. */
abstract class FaceEmbedder : Closeable {
    abstract val kind: ModelKind
    abstract val inputWidth: Int
    abstract val inputHeight: Int
    /** Kennung von Modell + Ausrichtung; ändert sie sich, wird alles neu gescannt */
    abstract val modelId: String
    abstract fun embed(face: Bitmap): FloatArray

    companion object {
        const val ONNX_FILE = "face_model.onnx"
        const val TFLITE_FILE = "face_model.tflite"
        /** Erhöhen, wenn sich die Ausrichtung/Vorverarbeitung ändert */
        const val ALIGN_VERSION = 2

        private fun read(context: Context, name: String): ByteArray? = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (_: Exception) {
            File(context.filesDir, name).takeIf { it.exists() }?.readBytes()
        }

        /** Bevorzugt face_model.onnx (InsightFace), sonst face_model.tflite (MobileFaceNet). */
        fun create(context: Context): FaceEmbedder? {
            read(context, ONNX_FILE)?.let { bytes -> OnnxEmbedder.create(bytes)?.let { return it } }
            read(context, TFLITE_FILE)?.let { bytes -> TfliteEmbedder.create(bytes)?.let { return it } }
            return null
        }

        private fun exists(context: Context, name: String) =
            try { context.assets.open(name).close(); true } catch (_: Exception) { File(context.filesDir, name).exists() }

        fun modelAvailable(context: Context): Boolean = exists(context, ONNX_FILE) || exists(context, TFLITE_FILE)

        fun normalize(e: FloatArray): FloatArray = normalized(e)
    }
}

/** InsightFace ArcFace-Modelle (z.B. w600k_mbf.onnx aus buffalo_s): Eingabe NCHW, (x-127.5)/127.5, RGB. */
class OnnxEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val nchw: Boolean,
    override val inputHeight: Int,
    override val inputWidth: Int,
    size: Int,
) : FaceEmbedder() {
    override val kind = ModelKind.INSIGHTFACE
    override val modelId = "onnx:$size:a$ALIGN_VERSION"
    private val pixels = IntArray(inputWidth * inputHeight)
    private val data = FloatArray(3 * inputWidth * inputHeight)

    override fun embed(face: Bitmap): FloatArray {
        val w = inputWidth; val h = inputHeight
        face.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16 and 0xFF) - 127.5f) / 127.5f
            val g = ((p shr 8 and 0xFF) - 127.5f) / 127.5f
            val b = ((p and 0xFF) - 127.5f) / 127.5f
            if (nchw) {
                data[i] = r; data[plane + i] = g; data[2 * plane + i] = b
            } else {
                data[3 * i] = r; data[3 * i + 1] = g; data[3 * i + 2] = b
            }
        }
        val shape = if (nchw) longArrayOf(1, 3, h.toLong(), w.toLong()) else longArrayOf(1, h.toLong(), w.toLong(), 3)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result.get(0).value as Array<FloatArray>)[0]
                normalized(out.copyOf())
            }
        }
    }

    override fun close() = session.close()

    companion object {
        fun create(bytes: ByteArray): OnnxEmbedder? = try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            val session = env.createSession(bytes, opts)
            val name = session.inputNames.first()
            val shape = (session.inputInfo[name]!!.info as TensorInfo).shape
            val nchw = shape.size == 4 && shape[1] == 3L
            fun dim(v: Long) = if (v > 0) v.toInt() else 112
            val h = if (nchw) dim(shape[2]) else dim(shape[1])
            val w = if (nchw) dim(shape[3]) else dim(shape[2])
            OnnxEmbedder(env, session, name, nchw, h, w, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}

/** MobileFaceNet als TFLite: Eingabe NHWC, (x-127.5)/128. */
class TfliteEmbedder private constructor(private val interpreter: Interpreter, size: Int) : FaceEmbedder() {
    override val kind = ModelKind.MOBILEFACENET
    override val modelId = "tflite:$size:a$ALIGN_VERSION"
    private val inShape = interpreter.getInputTensor(0).shape() // [batch, h, w, 3]
    private val batch = inShape[0].coerceAtLeast(1)
    override val inputHeight = inShape[1]
    override val inputWidth = inShape[2]
    private val dim = interpreter.getOutputTensor(0).shape().last()
    private val input = ByteBuffer.allocateDirect(4 * batch * inputHeight * inputWidth * 3).order(ByteOrder.nativeOrder())
    private val output = Array(batch) { FloatArray(dim) }
    private val pixels = IntArray(inputWidth * inputHeight)

    override fun embed(face: Bitmap): FloatArray {
        input.rewind()
        face.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (p in pixels) {
            input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        while (input.hasRemaining()) input.putFloat(0f) // weitere Batch-Plätze leer
        input.rewind()
        interpreter.run(input, output)
        return normalized(output[0].copyOf())
    }

    override fun close() = interpreter.close()

    companion object {
        fun create(bytes: ByteArray): TfliteEmbedder? = try {
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            val interpreter = Interpreter(buf, Interpreter.Options().setNumThreads(4))
            val t = interpreter.getInputTensor(0)
            if (t.dataType() != DataType.FLOAT32 || t.shape().size != 4 || t.shape()[3] != 3) {
                interpreter.close(); null
            } else TfliteEmbedder(interpreter, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}

// ====================================================================== Erkennung + Ausrichtung

object FaceAnalyzer {
    private const val DECODE_MAX = 1280
    /** Kleinere Gesichter (in Pixel bei 1280er Bild) werden ignoriert */
    private const val MIN_FACE_PX = 56
    private const val MAX_YAW = 30f
    private const val MAX_PITCH = 25f

    /** ArcFace-Standardvorlage für 112x112: linkes Auge, rechtes Auge, Nase, linker/rechter Mundwinkel (Bildseite). */
    private val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    fun createDetector(): FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
    )

    /** Läuft im Hintergrund-Thread. */
    fun analyze(context: Context, uri: Uri, detector: FaceDetector, embedder: FaceEmbedder): List<DetectedFace> {
        val bmp = decodeSoftware(context, uri, DECODE_MAX) ?: return emptyList()
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            return faces.mapNotNull { f ->
                val b = f.boundingBox
                if (b.width() < MIN_FACE_PX) return@mapNotNull null
                if (abs(f.headEulerAngleY) > MAX_YAW || abs(f.headEulerAngleX) > MAX_PITCH) return@mapNotNull null
                val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return@mapNotNull null
                val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return@mapNotNull null
                val nose = f.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return@mapNotNull null
                val ml = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return@mapNotNull null
                val mr = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return@mapNotNull null
                // Nach Bildseite sortieren (ML Kit benennt aus Sicht der Person)
                val (e1, e2) = if (le.x < re.x) le to re else re to le
                val (m1, m2) = if (ml.x < mr.x) ml to mr else mr to ml
                val crop = align(bmp, listOf(e1, e2, nose, m1, m2), embedder.inputWidth, embedder.inputHeight)
                try {
                    val sharp = sharpness(crop)
                    val e = embedder.embed(crop)
                    DetectedFace(
                        (b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                        (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f), e, sharp,
                    )
                } finally {
                    crop.recycle()
                }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            bmp.recycle()
        }
    }

    /** Ähnlichkeitstransformation (Drehen, Skalieren, Verschieben) der 5 Punkte auf die Vorlage. */
    private fun align(src: Bitmap, pts: List<PointF>, outW: Int, outH: Int): Bitmap {
        val sx = outW / 112f; val sy = outH / 112f
        var mx = 0f; var my = 0f; var mu = 0f; var mv = 0f
        for (i in 0 until 5) {
            mx += pts[i].x; my += pts[i].y
            mu += TEMPLATE[2 * i] * sx; mv += TEMPLATE[2 * i + 1] * sy
        }
        mx /= 5; my /= 5; mu /= 5; mv /= 5
        var num1 = 0f; var num2 = 0f; var den = 0f
        for (i in 0 until 5) {
            val x = pts[i].x - mx; val y = pts[i].y - my
            val u = TEMPLATE[2 * i] * sx - mu; val v = TEMPLATE[2 * i + 1] * sy - mv
            num1 += x * u + y * v
            num2 += x * v - y * u
            den += x * x + y * y
        }
        val a = if (den > 0f) num1 / den else 1f
        val bb = if (den > 0f) num2 / den else 0f
        val tx = mu - (a * mx - bb * my)
        val ty = mv - (bb * mx + a * my)
        val m = Matrix().apply { setValues(floatArrayOf(a, -bb, tx, bb, a, ty, 0f, 0f, 1f)) }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Schärfe als Varianz des Laplace-Filters auf Graustufen. */
    private fun sharpness(bmp: Bitmap): Float {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(px.size) {
            val p = px[it]
            0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
        }
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
            sum += l; sum2 += l * l; n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sum2 / n - mean * mean).toFloat()
    }
}

/** Dekodiert als Software-Bitmap (nötig für Pixelzugriff/Zuschneiden). */
fun decodeSoftware(context: Context, uri: Uri, maxDim: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width; val h = info.size.height
        val m = max(w, h)
        if (m > maxDim) {
            val f = maxDim.toFloat() / m
            decoder.setTargetSize(max(1, (w * f).toInt()), max(1, (h * f).toInt()))
        }
    }
} catch (_: Exception) {
    null
}

/** Runde Gesichts-Vorschaubilder für die Personen-Ansicht. */
object FaceAvatars {
    private val cache = LruCache<Long, Bitmap>(400)

    fun peek(faceId: Long): Bitmap? = cache.get(faceId)

    suspend fun load(context: Context, uri: Uri, face: FaceRef): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(face.id)?.let { return@withContext it }
        val bmp = decodeSoftware(context, uri, 1024) ?: return@withContext null
        var result: Bitmap? = null
        try {
            val w = bmp.width; val h = bmp.height
            val fw = (face.right - face.left) * w; val fh = (face.bottom - face.top) * h
            val side = (max(fw, fh) * 1.5f).toInt().coerceIn(1, min(w, h))
            val cx = (face.left + face.right) / 2f * w; val cy = (face.top + face.bottom) / 2f * h
            val x = (cx - side / 2f).toInt().coerceIn(0, w - side)
            val y = (cy - side / 2f).toInt().coerceIn(0, h - side)
            val crop = Bitmap.createBitmap(bmp, x, y, side, side)
            val scaled = Bitmap.createScaledBitmap(crop, 192, 192, true)
            if (crop !== scaled && crop !== bmp) crop.recycle()
            cache.put(face.id, scaled)
            result = scaled
            scaled
        } catch (_: Exception) {
            null
        } finally {
            if (bmp !== result) bmp.recycle()
        }
    }
}
