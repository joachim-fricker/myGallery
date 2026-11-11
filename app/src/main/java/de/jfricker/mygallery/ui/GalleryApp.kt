package de.jfricker.mygallery.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.CompositionLocalProvider
import de.jfricker.mygallery.data.favKey
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.jfricker.mygallery.GalleryViewModel
import de.jfricker.mygallery.ScanState
import de.jfricker.mygallery.data.Countries
import de.jfricker.mygallery.data.MapPoint
import de.jfricker.mygallery.data.MediaImage
import de.jfricker.mygallery.data.buildAlbums
import de.jfricker.mygallery.data.buildCountries
import de.jfricker.mygallery.data.yearOf

object Permissions {
    val ALL = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
    fun hasMedia(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
}

/** Einfacher Navigations-Stack über den Tabs. */
sealed class Screen {
    val gridState = LazyGridState()

    class AlbumDetail(val path: String, val year: Int, val isCamera: Boolean, val title: String) : Screen()
    class CountryDetail(val code: String, val title: String) : Screen()
    class Selection(val title: String, val ids: List<Long>) : Screen()
    class Viewer(val ids: List<Long>, val start: Int) : Screen()
    class PersonDetail(val personId: Int) : Screen()
    class Review(val personId: Int, val allMode: Boolean) : Screen()
    class Favorites : Screen()
    class PersonSearch : Screen()
}

private enum class Tab(val label: String, val icon: ImageVector) {
    ALL("Alle", Icons.Default.Home),
    ALBUMS("Alben", Icons.AutoMirrored.Filled.List),
    PERSONS("Personen", Icons.Default.Face),
    COUNTRIES("Länder", Icons.Default.Place),
    MAP("Karte", Icons.Default.LocationOn),
}

@Composable
fun GalleryApp(vm: GalleryViewModel) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(Permissions.hasMedia(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = Permissions.hasMedia(context)
        if (hasPermission) vm.refresh()
    }
    if (!hasPermission) {
        PermissionScreen { launcher.launch(Permissions.ALL) }
        return
    }
    LaunchedEffect(Unit) { vm.refresh() }

    val images by vm.images.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val geo by vm.geo.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val faces by vm.faces.collectAsStateWithLifecycle()
    val openGroups by vm.openGroups.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val favoriteImages = remember(images, favorites) { images.filter { it.favKey in favorites } }
    val showUnnamed by vm.showUnnamed.collectAsStateWithLifecycle()
    val facesBusy by vm.facesBusy.collectAsStateWithLifecycle()

    val byId = remember(images) { images.associateBy { it.id } }
    val albums = remember(images) { buildAlbums(images) }
    val countries = remember(images, geo) { buildCountries(images, geo) }
    val mapPoints = remember(images, geo) {
        images.mapNotNull { img ->
            geo[img.id]?.takeIf { it.hasLocation && it.dateModified == img.dateModified }
                ?.let { MapPoint(img.id, img.uri, it.lat, it.lon, img.date) }
        }
    }
    val persons = remember(images, faces) { buildPersons(images, faces) }
    val tagging = remember(faces, persons) {
        FaceTagging(
            facesOf = { id -> faces.facesByImage[id].orEmpty() },
            nameOf = { pid -> faces.names[pid] },
            persons = persons,
            assign = { faceId, pid -> vm.moveFaceToPerson(faceId, pid) },
            assignNew = { faceId, name -> vm.moveFacesToNewPerson(listOf(faceId), name) },
            confirm = { faceId, pid -> vm.moveFaceToPerson(faceId, pid) },
        )
    }
    val personNames: (Long) -> List<String> = { id ->
        faces.facesByImage[id]?.map { faces.names[it.personId] ?: "Unbenannt" }?.distinct() ?: emptyList()
    }
    val withoutGps = remember(images, geo) {
        images.count { img -> geo[img.id]?.let { !it.hasLocation } == true }
    }

    val stack = remember { mutableStateListOf<Screen>() }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun openViewer(list: List<MediaImage>, index: Int) = push(Screen.Viewer(list.map { it.id }, index))

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val allGrid = rememberLazyGridState()
    val albumGrid = rememberLazyGridState()
    val countryList = rememberLazyListState()
    val personGrid = rememberLazyGridState()

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    CompositionLocalProvider(LocalFavorites provides favorites) {
        when (val top = stack.lastOrNull()) {
            null -> RootScaffold(
                tab = Tab.entries[tab],
                onTab = { tab = it.ordinal },
                title = when (Tab.entries[tab]) {
                    Tab.ALL -> "Alle Bilder (${images.size})"
                    Tab.ALBUMS -> "Alben (${albums.sumOf { it.albums.size }})"
                    Tab.PERSONS -> "Personen (${persons.size})"
                    Tab.COUNTRIES -> "Länder (${countries.count { it.visited }})"
                    Tab.MAP -> "Karte (${mapPoints.size})"
                },
                scan = scan,
                onRefresh = { vm.refresh() },
                onRetry = { vm.retryScan() },
                extraActions = {
                    if (Tab.entries[tab] == Tab.PERSONS) {
                        IconButton(onClick = { push(Screen.PersonSearch()) }) {
                            Icon(Icons.Default.Search, contentDescription = "Personen suchen")
                        }
                    }
                },
            ) {
                if (loaded && images.isEmpty()) {
                    EmptyHint("Keine Bilder gefunden.\nGesucht wird in DCIM/Camera und Data/images.")
                } else when (Tab.entries[tab]) {
                    Tab.ALL -> ImageGrid(images, onOpen = { openViewer(images, it) }, state = allGrid)
                    Tab.ALBUMS -> AlbumsScreen(
                    albums, albumGrid,
                    favorites = favoriteImages,
                    onOpenFavorites = { push(Screen.Favorites()) },
                ) {
                        push(Screen.AlbumDetail(it.path, it.year, it.isCamera, if (it.isCamera) "Kamera ${it.year}" else it.name))
                    }
                    Tab.PERSONS -> PersonsScreen(
                        persons = persons,
                        groups = faces.groups,
                        modelMissing = faces.modelMissing,
                        state = personGrid,
                        openGroups = openGroups,
                        onGroupOpen = { gid, open -> vm.setGroupOpen(gid, open) },
                        onEditGroups = { vm.applyGroups(it) },
                        showUnnamed = showUnnamed,
                        onShowUnnamed = { vm.setShowUnnamed(it) },
                        onReorder = { vm.setPersonOrder(it) },
                        busy = facesBusy,
                        modelLabel = faces.modelLabel,
                    ) {
                        push(Screen.PersonDetail(it.id))
                    }
                    Tab.COUNTRIES -> CountriesScreen(countries, withoutGps, mapPoints.size, countryList, vm) {
                        push(Screen.CountryDetail(it.code, "${it.flag} ${it.name}"))
                    }
                    Tab.MAP -> MapScreen(mapPoints, vm) { title, ids -> push(Screen.Selection(title, ids)) }
                }
            }

            is Screen.AlbumDetail -> {
                val list = remember(images, top) {
                    images.filter { it.relativePath == top.path && (!top.isCamera || yearOf(it.date) == top.year) }
                }
                DetailScaffold(top.title, "${list.size} Bilder · ${top.path}", ::pop) { mod ->
                    ImageGrid(list, onOpen = { openViewer(list, it) }, modifier = mod, state = top.gridState)
                }
            }

            is Screen.CountryDetail -> {
                val list = remember(images, geo, top.code) { images.filter { geo[it.id]?.country == top.code } }
                DetailScaffold(top.title, "${list.size} Bilder", ::pop) { mod ->
                    ImageGrid(list, onOpen = { openViewer(list, it) }, modifier = mod, state = top.gridState)
                }
            }

            is Screen.Selection -> {
                val list = remember(byId, top.ids) { top.ids.mapNotNull { byId[it] } }
                DetailScaffold(top.title, "Auswahl auf der Karte", ::pop) { mod ->
                    ImageGrid(list, onOpen = { openViewer(list, it) }, modifier = mod, state = top.gridState)
                }
            }

            is Screen.PersonDetail -> {
                val all = remember(images, faces) { buildPersons(images, faces, includeSmall = true) }
                val person = all.firstOrNull { it.id == top.personId }
                if (person == null) {
                    LaunchedEffect(Unit) { pop() }
                } else {
                    var menu by remember { mutableStateOf(false) }
                    var rename by remember { mutableStateOf(false) }
                    var merge by remember { mutableStateOf(false) }
                    // Mehrfachauswahl (Bild-IDs)
                    val selected = remember { mutableStateListOf<Long>() }
                    val selecting = selected.isNotEmpty()
                    var assign by remember { mutableStateOf(false) }
                    var newPerson by remember { mutableStateOf(false) }
                    var confirmRemove by remember { mutableStateOf(false) }
                    var pickGroup by remember { mutableStateOf(false) }

                    // Gesichter dieser Person auf den ausgewählten Bildern
                    fun selectedFaceIds(): List<Long> = selected.flatMap { imgId ->
                        faces.facesByImage[imgId]?.filter { it.personId == person.id }?.map { it.id } ?: emptyList()
                    }
                    fun toggle(id: Long) { if (id in selected) selected.remove(id) else selected.add(id) }

                    // Zurück-Taste beendet zuerst die Auswahl
                    BackHandler(enabled = selecting) { selected.clear() }

                    DetailScaffold(
                        title = if (selecting) "${selected.size} ausgewählt"
                        else person.displayName,
                        subtitle = if (selecting) person.displayName
                        else "${person.images.size} Bilder · lange drücken = auswählen",
                        onBack = { if (selecting) selected.clear() else pop() },
                        actions = {
                            if (selecting) {
                                TextButton(onClick = {
                                    if (selected.size == person.images.size) selected.clear()
                                    else { selected.clear(); selected.addAll(person.images.map { it.id }) }
                                }) { Text(if (selected.size == person.images.size) "Keine" else "Alle") }
                            } else {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menü") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (person.name != null) {
                                        DropdownMenuItem(
                                            text = { Text("Mit weiteren Personen …") },
                                            onClick = {
                                                menu = false
                                                vm.searchPersons.clear()
                                                vm.searchPersons.add(person.id)
                                                push(Screen.PersonSearch())
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Vorschläge prüfen (${person.suggestions.size})") },
                                            onClick = { menu = false; push(Screen.Review(person.id, false)) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Automatische prüfen (${person.autoCount})") },
                                            onClick = { menu = false; push(Screen.Review(person.id, true)) },
                                        )
                                    }
                                    DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { menu = false; rename = true })
                                    DropdownMenuItem(text = { Text("Zusammenführen mit …") }, onClick = { menu = false; merge = true })
                                    if (person.name != null) {
                                        DropdownMenuItem(
                                            text = { Text("Gruppe: ${person.groupName ?: "Haupt"} …") },
                                            onClick = { menu = false; pickGroup = true },
                                        )
                                    }
                                }
                            }
                        },
                        bottomBar = {
                            if (selecting) {
                                BottomAppBar {
                                    TextButton(
                                        onClick = {
                                            selectedFaceIds().firstOrNull()?.let { vm.setPersonCover(person.id, it) }
                                            selected.clear()
                                        },
                                        enabled = selected.size == 1,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Titelbild") }
                                    TextButton(onClick = { assign = true }, modifier = Modifier.weight(1f)) {
                                        Text("Zuordnen …")
                                    }
                                    TextButton(onClick = { confirmRemove = true }, modifier = Modifier.weight(1f)) {
                                        Text("Entfernen")
                                    }
                                }
                            }
                        },
                    ) { mod ->
                        Column(mod) {
                            if (!selecting && person.name != null && person.suggestions.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable { push(Screen.Review(person.id, false)) },
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "${person.suggestions.size} Vorschläge prüfen",
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                "Ist das ${person.displayName}? Je mehr du bestätigst, desto besser wird die Erkennung.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        TextButton(onClick = { push(Screen.Review(person.id, false)) }) { Text("Prüfen") }
                                    }
                                }
                            }
                            ImageGrid(
                                person.images,
                                onOpen = { i ->
                                    if (selecting) toggle(person.images[i].id) else openViewer(person.images, i)
                                },
                                onLongPress = { i -> toggle(person.images[i].id) },
                                selected = selected.toSet(),
                                modifier = Modifier.weight(1f),
                                state = top.gridState,
                            )
                        }
                    }

                    if (pickGroup) {
                        GroupPickerDialog(
                            groups = faces.groups,
                            current = person.groupId,
                            onDismiss = { pickGroup = false },
                            onCreate = { name -> vm.createGroup(name, person.id); pickGroup = false },
                        ) { g ->
                            vm.setPersonGroup(person.id, g.id)
                            pickGroup = false
                        }
                    }
                    if (rename) RenameDialog(person.name, onDismiss = { rename = false }) {
                        vm.renamePerson(person.id, it); rename = false
                    }
                    if (merge) MergeDialog(all.filter { it.id != person.id }, onDismiss = { merge = false }) { target ->
                        vm.mergePersons(person.id, target.id)
                        merge = false
                        pop()
                        push(Screen.PersonDetail(target.id))
                    }
                    if (assign) {
                        PersonPickerDialog(
                            "Wer ist auf ${if (selected.size == 1) "dem Bild" else "den ${selected.size} Bildern"}?",
                            all.filter { it.id != person.id && (it.name != null || it.images.size >= 3) },
                            onDismiss = { assign = false },
                            onCreateNew = { typed ->
                                if (typed.isNotBlank()) {
                                    vm.moveFacesToNewPerson(selectedFaceIds(), typed)
                                    selected.clear()
                                } else newPerson = true
                                assign = false
                            },
                        ) { target ->
                            vm.moveFacesToPerson(selectedFaceIds(), target.id)
                            selected.clear()
                            assign = false
                        }
                    }
                    if (newPerson) {
                        RenameDialog(null, onDismiss = { newPerson = false }) { name ->
                            vm.moveFacesToNewPerson(selectedFaceIds(), name)
                            selected.clear()
                            newPerson = false
                        }
                    }
                    if (confirmRemove) {
                        AlertDialog(
                            onDismissRequest = { confirmRemove = false },
                            title = { Text("Aus ${person.displayName} entfernen?") },
                            text = { Text("${selected.size} Bild(er) gehören nicht zu ${person.displayName}.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.removeFacesFromPerson(selectedFaceIds())
                                    selected.clear()
                                    confirmRemove = false
                                }) { Text("Entfernen") }
                            },
                            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Abbrechen") } },
                        )
                    }
                }
            }

            is Screen.Review -> {
                val all = remember(images, faces) { buildPersons(images, faces, includeSmall = true) }
                val person = all.firstOrNull { it.id == top.personId }
                if (person == null) {
                    LaunchedEffect(Unit) { pop() }
                } else {
                    ReviewScreen(
                        person = person,
                        allMode = top.allMode,
                        onSubmit = { confirm, reject -> vm.reviewFaces(person.id, confirm, reject) },
                        onOpenImage = { img -> openViewer(listOf(img), 0) },
                        onBack = ::pop,
                    )
                }
            }

            is Screen.PersonSearch -> {
                val perImage = remember(images, faces) { namedPersonsPerImage(images, faces) }
                val selectedIds = vm.searchPersons.filter { pid -> persons.any { it.id == pid && it.name != null } }
                val personHits = remember(images, perImage, selectedIds, vm.searchOnlyThese) {
                    searchByPersons(images, perImage, selectedIds.toSet(), vm.searchOnlyThese)
                }
                // Land/Jahr je Treffer
                val countryOf: (MediaImage) -> String? = { img ->
                    geo[img.id]?.takeIf { it.dateModified == img.dateModified && it.country.isNotEmpty() && it.country != "-" }?.country
                }
                val filter = remember(personHits, geo, vm.searchCountry, vm.searchYear) {
                    personFilter(personHits, countryOf, vm.searchCountry, vm.searchYear)
                }
                val results = filter.results
                val names = selectedIds.mapNotNull { pid -> persons.firstOrNull { it.id == pid }?.displayName }
                val extra = buildList {
                    vm.searchCountry?.let { add(Countries.name(it)) }
                    vm.searchYear?.let { add(it.toString()) }
                    if (vm.searchOnlyThese) add("nur diese Personen")
                }
                DetailScaffold(
                    title = if (names.isEmpty()) "Personen suchen" else names.joinToString(" + "),
                    subtitle = if (names.isEmpty()) "Personen antippen" else
                        (listOf("${results.size} Bilder") + extra).joinToString(" · "),
                    onBack = ::pop,
                ) { mod ->
                    PersonSearchContent(
                        persons = persons,
                        perImage = perImage,
                        selected = selectedIds,
                        onToggle = { vm.toggleSearchPerson(it) },
                        onlyThese = vm.searchOnlyThese,
                        onOnlyThese = { vm.searchOnlyThese = it },
                        filter = filter,
                        country = vm.searchCountry,
                        onCountry = { vm.searchCountry = it },
                        year = vm.searchYear,
                        onYear = { vm.searchYear = it },
                        gridState = top.gridState,
                        onOpen = { openViewer(results, it) },
                        modifier = mod,
                    )
                }
            }

            is Screen.Favorites -> {
                val selected = remember { mutableStateListOf<Long>() }
                val selecting = selected.isNotEmpty()
                BackHandler(enabled = selecting) { selected.clear() }
                DetailScaffold(
                    title = if (selecting) "${selected.size} ausgewählt" else "Favoriten",
                    subtitle = if (selecting) "Favoriten" else "${favoriteImages.size} Bilder · lange drücken = auswählen",
                    onBack = { if (selecting) selected.clear() else pop() },
                    actions = {
                        if (selecting) TextButton(onClick = {
                            if (selected.size == favoriteImages.size) selected.clear()
                            else { selected.clear(); selected.addAll(favoriteImages.map { it.id }) }
                        }) { Text(if (selected.size == favoriteImages.size) "Keine" else "Alle") }
                    },
                    bottomBar = {
                        if (selecting) BottomAppBar {
                            TextButton(
                                onClick = {
                                    vm.setFavorite(favoriteImages.filter { it.id in selected }, false)
                                    selected.clear()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Aus Favoriten entfernen") }
                        }
                    },
                ) { mod ->
                    if (favoriteImages.isEmpty()) {
                        EmptyHint("Noch keine Favoriten.\nIm Vollbild auf das Herz tippen.", mod)
                    } else {
                        ImageGrid(
                            favoriteImages,
                            onOpen = { i ->
                                val img = favoriteImages[i]
                                if (selecting) { if (img.id in selected) selected.remove(img.id) else selected.add(img.id) }
                                else openViewer(favoriteImages, i)
                            },
                            onLongPress = { i ->
                                val id = favoriteImages[i].id
                                if (id in selected) selected.remove(id) else selected.add(id)
                            },
                            selected = selected.toSet(),
                            modifier = mod,
                            state = top.gridState,
                        )
                    }
                }
            }

            is Screen.Viewer -> {
                val list = remember(byId, top.ids) { top.ids.mapNotNull { byId[it] } }
                ViewerScreen(
                    list, top.start, geo,
                    onBack = ::pop,
                    personNames = personNames,
                    onToggleFavorite = { vm.toggleFavorite(it) },
                    tagging = if (faces.modelMissing) null else tagging,
                    onShowOnMap = { lat, lon ->
                        // Grosse Karte auf das Bild zentrieren
                        vm.mapLat = lat
                        vm.mapLon = lon
                        vm.mapZoom = 16.0
                        stack.clear()
                        tab = Tab.MAP.ordinal
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScaffold(
    tab: Tab,
    onTab: (Tab) -> Unit,
    title: String,
    scan: ScanState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    extraActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    extraActions()
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScanBanner(scan, onRetry)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun ScanBanner(scan: ScanState, onRetry: () -> Unit) {
    if (scan.running) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                if (scan.total > 0) "${scan.phase}: ${scan.done} / ${scan.total}" else scan.phase,
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { if (scan.total > 0) scan.done.toFloat() / scan.total else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    } else if (scan.error != null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(scan.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Erneut") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                actions = actions,
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("myGallery", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Die App braucht Zugriff auf deine Fotos und deren Aufnahmeort (für Länder und Karte).",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Zugriff erlauben") }
    }
}
