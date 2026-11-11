package de.jfricker.mygallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.jfricker.mygallery.data.CountryLookup
import de.jfricker.mygallery.data.CountryShapes
import de.jfricker.mygallery.data.DateSource
import de.jfricker.mygallery.data.FaceAnalyzer
import de.jfricker.mygallery.data.FaceDb
import de.jfricker.mygallery.data.FaceEmbedder
import de.jfricker.mygallery.data.FaceSnapshot
import de.jfricker.mygallery.data.FavoritesStore
import de.jfricker.mygallery.data.favKey
import de.jfricker.mygallery.data.GroupDef
import de.jfricker.mygallery.data.dateFromFolder
import de.jfricker.mygallery.data.GeoEntry
import de.jfricker.mygallery.data.LocationReader
import de.jfricker.mygallery.data.LocationStore
import de.jfricker.mygallery.data.MediaImage
import de.jfricker.mygallery.data.MediaRepository
import de.jfricker.mygallery.data.WindowsExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LocationStore(File(app.filesDir, "locations.tsv"))
    private var cache: MutableMap<Long, GeoEntry>? = null
    private val scanMutex = Mutex()
    private var scanJob: Job? = null

    private val _images = MutableStateFlow<List<MediaImage>>(emptyList())
    val images: StateFlow<List<MediaImage>> = _images

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    private val _geo = MutableStateFlow<Map<Long, GeoEntry>>(emptyMap())
    val geo: StateFlow<Map<Long, GeoEntry>> = _geo

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan

    // Kartenposition merken, wenn man von der Karte weg navigiert
    var mapLat = 0.0
    var mapLon = 0.0
    var mapZoom = 0.0

    // ---------- Personen-Suche ----------
    /** ausgewählte Personen (IDs) – bleibt erhalten, wenn man die Suche verlässt */
    val searchPersons = mutableStateListOf<Int>()
    /** nur Bilder mit genau diesen benannten Personen */
    var searchOnlyThese by mutableStateOf(false)
    /** Personen-Suche zusätzlich auf ein Land (ISO-Code) / Jahr einschränken; null = alle */
    var searchCountry by mutableStateOf<String?>(null)
    var searchYear by mutableStateOf<Int?>(null)

    fun toggleSearchPerson(id: Int) {
        if (id in searchPersons) searchPersons.remove(id) else searchPersons.add(id)
    }

    // ---------- Länder (Liste / Karte) ----------
    /** Länder-Tab zeigt die Karte statt der Liste */
    var countryShowMap by mutableStateOf(false)
    /** Liste nach Anzahl Bilder statt A–Z sortieren */
    var countrySortByImages by mutableStateOf(false)
    /** auf der Länderkarte ausgewähltes Land (ISO-Code) */
    var countrySelected by mutableStateOf<String?>(null)
    /** Land, auf das die Länderkarte beim nächsten Anzeigen zentriert */
    var countryFocus by mutableStateOf<String?>(null)
    var countryMapLat = 30.0
    var countryMapLon = 10.0
    var countryMapZoom = 0.0

    private val _countryShapes = MutableStateFlow<CountryShapes?>(null)
    /** Grenzen zum Zeichnen der Länderkarte (lazy aus assets/countries_map.geojson) */
    val countryShapes: StateFlow<CountryShapes?> = _countryShapes

    fun loadCountryShapes() {
        if (_countryShapes.value != null) return
        viewModelScope.launch {
            _countryShapes.value = try { CountryShapes.get(getApplication()) } catch (_: Exception) { null }
        }
    }

    /** Name aus den Kartendaten (für Länder ohne Bilder, z.B. "Noch nicht besucht") */
    fun countryShapeName(code: String): String? = _countryShapes.value?.byCode?.get(code)?.name

    /** Bilder so wie vom MediaStore geliefert (ohne EXIF-Datum) */
    @Volatile private var rawImages: List<MediaImage> = emptyList()

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                try { MediaRepository.loadImages(getApplication()) } catch (_: Exception) { emptyList() }
            }
            val changed = list != rawImages
            rawImages = list
            publishImages(_geo.value)
            _loaded.value = true
            if (changed || scanJob == null) startScan(list)
        }
    }

    fun retryScan() = startScan(rawImages)

    /**
     * Datum festlegen und neu sortieren (neueste zuoberst):
     * EXIF-Datum > Datum aus Ordnername > MediaStore DATE_TAKEN > Hinzugefügt-Datum.
     * Ordner-Datum erst, wenn der Scan bestätigt hat, dass kein EXIF-Datum vorhanden ist.
     */
    private fun publishImages(geo: Map<Long, GeoEntry>) {
        _images.value = rawImages.map { img ->
            val e = geo[img.id]?.takeIf { it.dateModified == img.dateModified }
            when {
                e == null -> img
                e.exifDate > 0 -> img.copy(date = e.exifDate, exifDate = e.exifDate, dateSource = DateSource.EXIF)
                else -> dateFromFolder(img)?.let { img.copy(date = it, dateSource = DateSource.FOLDER) } ?: img
            }
        }.sortedByDescending { it.date }
    }

    /** Snapshot des Caches veröffentlichen (Geo-Daten + korrigierte Bilddaten). */
    private fun publish(c: Map<Long, GeoEntry>) {
        val snap = HashMap(c)
        _geo.value = snap
        publishImages(snap)
    }

    private fun startScan(list: List<MediaImage>) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            scanMutex.withLock { scan(list) }
        }
    }

    private suspend fun scan(list: List<MediaImage>) {
        val ctx = getApplication<Application>()
        val c = cache ?: store.load().also { cache = it }

        // Neue Grenzdaten -> bereits zugeordnete Länder verwerfen und neu berechnen
        val prefs = ctx.getSharedPreferences("geo", android.content.Context.MODE_PRIVATE)
        val versionOk = prefs.getInt("countryVersion", 1) == CountryLookup.DATA_VERSION
        if (!versionOk) {
            for ((id, e) in c.entries.toList()) if (e.hasLocation) c[id] = e.copy(country = "")
        }
        publish(c)

        // 1. GPS + Aufnahmedatum aus EXIF lesen (nur neue / geänderte Bilder)
        val todo = list.filter { val e = c[it.id]; e == null || e.dateModified != it.dateModified }
        todo.forEachIndexed { i, img ->
            currentCoroutineContext().ensureActive()
            val meta = LocationReader.read(ctx, img.uri)
            val ll = meta.latLon
            c[img.id] = GeoEntry(
                dateModified = img.dateModified,
                lat = ll?.get(0) ?: Double.NaN,
                lon = ll?.get(1) ?: Double.NaN,
                country = if (ll == null) "-" else "",
                exifDate = meta.date,
            )
            if (i % 20 == 0) _scan.value = ScanState(true, "EXIF-Daten lesen", i, todo.size)
            if (i % 300 == 299) {
                store.save(c); publish(c)
            }
        }
        // gelöschte Bilder aus dem Cache entfernen
        val ids = list.mapTo(HashSet()) { it.id }
        c.keys.retainAll(ids)
        store.save(c)
        publish(c)

        // 2. Länder bestimmen
        var error: String? = null
        val needCountry = c.filter { it.value.hasLocation && it.value.country.isEmpty() }
        if (needCountry.isNotEmpty()) {
            _scan.value = ScanState(true, "Ländergrenzen laden", 0, needCountry.size)
            val lookup = CountryLookup.get(ctx)
            if (lookup == null) {
                error = "Ländergrenzen konnten nicht geladen werden. Für den ersten Start wird Internet benötigt " +
                    "(oder countries_10m.geojson in app/src/main/assets legen)."
            } else {
                needCountry.entries.forEachIndexed { i, (id, e) ->
                    currentCoroutineContext().ensureActive()
                    c[id] = e.copy(country = lookup.find(e.lat, e.lon) ?: "-")
                    if (i % 50 == 0) _scan.value = ScanState(true, "Länder zuordnen", i, needCountry.size)
                }
                store.save(c)
                publish(c)
            }
        }
        if (!versionOk && error == null) prefs.edit().putInt("countryVersion", CountryLookup.DATA_VERSION).apply()

        // 3. Gesichter erkennen und Personen gruppieren
        scanFaces(ctx, list)

        _scan.value = ScanState(error = error)
    }

    // ---------------- Favoriten ----------------

    private val favStore = FavoritesStore(File(app.filesDir, "favorites.txt"))
    private val _favorites = MutableStateFlow(favStore.load())
    val favorites: StateFlow<Set<String>> = _favorites

    fun toggleFavorite(img: MediaImage) = setFavorite(listOf(img), img.favKey !in _favorites.value)

    fun setFavorite(images: List<MediaImage>, favorite: Boolean) {
        val keys = images.map { it.favKey }
        val next = if (favorite) _favorites.value + keys else _favorites.value - keys.toSet()
        _favorites.value = next
        viewModelScope.launch(Dispatchers.IO) { favStore.save(next) }
    }

    // ---------------- Personen ----------------

    private val faceDb = FaceDb(File(app.filesDir, "faces"))
    private var faceDbLoaded = false
    private val faceMutex = Mutex()

    private val _faces = MutableStateFlow(FaceSnapshot())
    val faces: StateFlow<FaceSnapshot> = _faces

    private val facePrefs = app.getSharedPreferences("faces", android.content.Context.MODE_PRIVATE)

    /** true, während Vorschläge im Hintergrund neu berechnet werden */
    private val _facesBusy = MutableStateFlow(false)
    val facesBusy: StateFlow<Boolean> = _facesBusy
    private var reclassifyJob: Job? = null

    private fun ensureFaceDb() {
        if (!faceDbLoaded) {
            faceDb.load()
            faceDbLoaded = true
        }
    }

    private fun publishFaces(modelMissing: Boolean = _faces.value.modelMissing) {
        _faces.value = faceDb.snapshot(modelMissing)
    }

    private suspend fun scanFaces(ctx: Application, list: List<MediaImage>) {
        val embedder = if (FaceEmbedder.modelAvailable(ctx)) FaceEmbedder.create(ctx) else null
        if (embedder == null) {
            faceMutex.withLock { ensureFaceDb(); publishFaces(true) }
            return
        }
        val todo = faceMutex.withLock {
            ensureFaceDb()
            // Anderes Modell oder neue Ausrichtung -> alles neu scannen, Namen werden übernommen
            if (faceDb.modelId != embedder.modelId) {
                faceDb.prepareRescan(embedder.modelId, embedder.kind)
                faceDb.save()
            }
            faceDb.prune(list)
            publishFaces(false)
            list.filter { !faceDb.isDone(it) }
        }
        if (todo.isEmpty()) {
            embedder.close()
            return
        }
        val detector = FaceAnalyzer.createDetector()
        try {
            faceMutex.withLock { faceDb.refreshAnchors() }
            todo.forEachIndexed { i, img ->
                currentCoroutineContext().ensureActive()
                if (i % 5 == 0) _scan.value = ScanState(true, "Gesichter erkennen", i, todo.size)
                val detected = FaceAnalyzer.analyze(ctx, img.uri, detector, embedder)
                faceMutex.withLock {
                    faceDb.addImage(img, detected)
                    if (i % 100 == 99) {
                        faceDb.save(); publishFaces(false)
                    }
                    if (i % 300 == 299) faceDb.refreshAnchors()
                }
            }
            _scan.value = ScanState(true, "Personen zuordnen")
            faceMutex.withLock { faceDb.reclassifyAll() }
        } finally {
            detector.close()
            embedder.close()
            withContext(NonCancellable) {
                faceMutex.withLock { faceDb.save(); publishFaces(false) }
            }
        }
    }

    /**
     * Änderung ausführen, sofort anzeigen und (verzögert, gebündelt) alle Vorschläge neu berechnen,
     * damit die App aus deinen Bestätigungen lernt.
     */
    private fun editFaces(reclassify: Boolean = false, block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            faceMutex.withLock {
                ensureFaceDb()
                block()
                faceDb.save()
                publishFaces()
            }
            if (reclassify) scheduleReclassify()
        }
    }

    private fun scheduleReclassify() {
        reclassifyJob?.cancel()
        reclassifyJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1500)
            _facesBusy.value = true
            try {
                faceMutex.withLock {
                    faceDb.reclassifyAll()
                    faceDb.save()
                    publishFaces()
                }
            } finally {
                _facesBusy.value = false
            }
        }
    }

    fun renamePerson(personId: Int, name: String) = editFaces(true) { faceDb.rename(personId, name) }
    fun setPersonGroup(personId: Int, groupId: Int) = editFaces { faceDb.setGroup(personId, groupId) }

    /** Neue Gruppe anlegen und (optional) die Person gleich hineinlegen. */
    fun createGroup(name: String, personId: Int? = null) = editFaces {
        if (name.isBlank()) return@editFaces
        val gid = faceDb.createGroup(name)
        if (personId != null) faceDb.setGroup(personId, gid)
        setGroupOpen(gid, true)
    }
    fun renameGroup(groupId: Int, name: String) = editFaces { faceDb.renameGroup(groupId, name) }
    fun deleteGroup(groupId: Int) = editFaces { faceDb.deleteGroup(groupId) }
    fun applyGroups(edited: List<GroupDef>) = editFaces { faceDb.applyGroups(edited) }
    fun setPersonOrder(ids: List<Int>) = editFaces { faceDb.setOrder(ids) }
    fun mergePersons(from: Int, into: Int) = editFaces(true) { faceDb.merge(from, into) }
    fun setPersonCover(personId: Int, faceId: Long) = editFaces(true) { faceDb.setCover(personId, faceId) }

    /** Gesichter gehören zu [personId] (bestätigt). Gilt auch zum Verschieben zu einer anderen Person. */
    fun moveFacesToPerson(faceIds: List<Long>, personId: Int) = editFaces(true) { faceDb.confirm(faceIds, personId) }
    fun moveFaceToPerson(faceId: Long, personId: Int) = moveFacesToPerson(listOf(faceId), personId)

    /** Gesichter gehören nicht zu ihrer aktuellen Person. */
    fun removeFacesFromPerson(faceIds: List<Long>) = editFaces(true) { faceDb.reject(faceIds) }
    fun removeFaceFromPerson(faceId: Long) = removeFacesFromPerson(listOf(faceId))

    /** Ergebnis einer Prüf-Runde: [confirm] gehören zu [personId], [reject] nicht. */
    fun reviewFaces(personId: Int, confirm: List<Long>, reject: List<Long>) = editFaces(true) {
        if (confirm.isNotEmpty()) faceDb.confirm(confirm, personId)
        if (reject.isNotEmpty()) faceDb.reject(reject)
    }

    /** Neue Person mit [name] erstellen und die Gesichter dorthin verschieben (erstes Gesicht = Titelbild). */
    fun moveFacesToNewPerson(faceIds: List<Long>, name: String) = editFaces(true) {
        if (faceIds.isEmpty()) return@editFaces
        val pid = faceDb.createPerson(name)
        faceDb.confirm(faceIds, pid)
        faceDb.setCover(pid, faceIds.first())
    }

    // Aufgeklappte Gruppen und Block "Ohne Namen" (gespeichert)
    private val _openGroups = MutableStateFlow(
        facePrefs.getString("openGroups", "")!!.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    )
    val openGroups: StateFlow<Set<Int>> = _openGroups

    fun setGroupOpen(groupId: Int, open: Boolean) {
        val next = if (open) _openGroups.value + groupId else _openGroups.value - groupId
        _openGroups.value = next
        facePrefs.edit().putString("openGroups", next.joinToString(",")).apply()
    }

    private val _showUnnamed = MutableStateFlow(facePrefs.getBoolean("showUnnamed", true))
    val showUnnamed: StateFlow<Boolean> = _showUnnamed

    fun setShowUnnamed(show: Boolean) {
        _showUnnamed.value = show
        facePrefs.edit().putBoolean("showUnnamed", show).apply()
    }

    /** Export aller eigenen Daten für myGallery unter Windows (ZIP im Download-Ordner). */
    fun exportForWindows(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val name = faceMutex.withLock {
                        ensureFaceDb()
                        faceDb.save()
                        WindowsExport.export(getApplication(), _images.value)
                    }
                    "Exportiert: Download/$name"
                } catch (e: Exception) {
                    "Export fehlgeschlagen: ${e.message}"
                }
            }
            onDone(msg)
        }
    }
}
