package de.jfricker.mygallery.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Shape
import de.jfricker.mygallery.data.FaceStatus
import de.jfricker.mygallery.data.GroupDef
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.jfricker.mygallery.data.FaceAvatars
import de.jfricker.mygallery.data.FaceDb
import de.jfricker.mygallery.data.FaceRef
import de.jfricker.mygallery.data.FaceSnapshot
import de.jfricker.mygallery.data.MediaImage
import java.text.Collator

/** Eine Person mit allen Bildern, auf denen sie erkannt wurde (neueste zuerst). */
data class PersonGroup(
    val id: Int,
    val name: String?,
    /** Bilder mit sicherer Zuordnung (bestätigt oder automatisch) */
    val images: List<MediaImage>,
    /** Gesicht für das Vorschaubild */
    val cover: FaceRef,
    val coverImage: MediaImage,
    /** Gruppe (nur benannte Personen) */
    val groupId: Int? = null,
    val groupName: String? = null,
    val order: Int = Int.MAX_VALUE,
    /** sicher zugeordnete Gesichter (bestätigt + automatisch) */
    val faces: List<Pair<MediaImage, FaceRef>> = emptyList(),
    /** Vorschläge, die noch geprüft werden müssen */
    val suggestions: List<Pair<MediaImage, FaceRef>> = emptyList(),
) {
    val displayName: String get() = name ?: "Unbenannt"
    val autoCount: Int get() = faces.count { it.second.status == FaceStatus.AUTO }
}

/** Alles, was die Bild-Details brauchen, um Gesichter Personen zuzuordnen. */
class FaceTagging(
    val facesOf: (imageId: Long) -> List<FaceRef>,
    val nameOf: (personId: Int) -> String?,
    /** Auswahl im Dialog (benannte + grössere unbenannte Gruppen) */
    val persons: List<PersonGroup>,
    val assign: (faceId: Long, personId: Int) -> Unit,
    val assignNew: (faceId: Long, name: String) -> Unit,
    /** Vorschlag bestätigen */
    val confirm: (faceId: Long, personId: Int) -> Unit = assign,
)

/** Unbenannte Gruppen erst ab so vielen Bildern anzeigen (Einzelgesichter sind meist Zufallstreffer). */
private const val MIN_IMAGES_UNNAMED = 3

/**
 * Reihenfolge: benannte Personen nach Gruppen (Gruppen-Reihenfolge, darin eigene Reihenfolge), dann unbenannte (nach Anzahl).
 */
fun buildPersons(images: List<MediaImage>, snap: FaceSnapshot, includeSmall: Boolean = false): List<PersonGroup> {
    val main = LinkedHashMap<Int, MutableList<Pair<MediaImage, FaceRef>>>()
    val sugg = HashMap<Int, MutableList<Pair<MediaImage, FaceRef>>>()
    for (img in images) {
        snap.facesByImage[img.id]?.forEach { f ->
            val named = f.personId in snap.names
            when {
                named && f.status == FaceStatus.SUGGESTED -> sugg.getOrPut(f.personId) { ArrayList() }.add(img to f)
                named && (f.status == FaceStatus.AUTO || f.status == FaceStatus.CONFIRMED) ->
                    main.getOrPut(f.personId) { ArrayList() }.add(img to f)
                !named && f.status == FaceStatus.UNNAMED -> main.getOrPut(f.personId) { ArrayList() }.add(img to f)
            }
        }
    }
    // benannte Personen, die (noch) nur Vorschläge haben
    for (pid in sugg.keys) main.getOrPut(pid) { ArrayList() }
    val collator = Collator.getInstance()
    val all = main.mapNotNull { (pid, list) ->
        val s = sugg[pid].orEmpty()
        val pool = list.ifEmpty { s }
        if (pool.isEmpty()) return@mapNotNull null
        val chosen = snap.covers[pid]?.let { fid -> pool.firstOrNull { it.second.id == fid } }
        val cover = chosen
            ?: pool.firstOrNull { it.second.status == FaceStatus.CONFIRMED }
            ?: pool.take(30).maxBy { it.second.area }
        PersonGroup(
            id = pid,
            name = snap.names[pid],
            images = list.map { it.first }.distinctBy { it.id },
            cover = cover.second,
            coverImage = cover.first,
            groupId = if (snap.names[pid] != null) snap.groupOf[pid] ?: FaceDb.MAIN_GROUP else null,
            groupName = if (snap.names[pid] != null)
                snap.groups.firstOrNull { it.id == (snap.groupOf[pid] ?: FaceDb.MAIN_GROUP) }?.name else null,
            order = snap.orders[pid] ?: Int.MAX_VALUE,
            faces = list,
            suggestions = s.sortedByDescending { it.second.score },
        )
    }.filter { includeSmall || it.name != null || it.images.size >= MIN_IMAGES_UNNAMED }

    val groupIndex = snap.groups.mapIndexed { i, g -> g.id to i }.toMap()
    val named = all.filter { it.name != null }
        .sortedWith(
            compareBy<PersonGroup> { groupIndex[it.groupId] ?: Int.MAX_VALUE }
                .thenBy { it.order }
                .then { a, b -> collator.compare(a.displayName, b.displayName) }
        )
    val unnamed = all.filter { it.name == null }.sortedByDescending { it.images.size }
    return named + unnamed
}

@Composable
fun FaceAvatar(image: MediaImage, face: FaceRef, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    val context = LocalContext.current
    val bmp by produceState(FaceAvatars.peek(face.id), face.id) {
        if (value == null) value = FaceAvatars.load(context, image.uri, face)
    }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let {
            val img = remember(it) { it.asImageBitmap() }
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun PersonsScreen(
    persons: List<PersonGroup>,
    groups: List<GroupDef>,
    modelMissing: Boolean,
    state: LazyGridState,
    openGroups: Set<Int>,
    onGroupOpen: (groupId: Int, open: Boolean) -> Unit,
    showUnnamed: Boolean,
    onShowUnnamed: (Boolean) -> Unit,
    onReorder: (List<Int>) -> Unit,
    busy: Boolean = false,
    modelLabel: String = "",
    onEditGroups: (List<GroupDef>) -> Unit = {},
    onOpen: (PersonGroup) -> Unit,
) {
    var manageGroups by remember { mutableStateOf(false) }
    if (manageGroups) {
        GroupManagerDialog(
            groups = groups,
            countOf = { gid -> persons.count { it.name != null && (it.groupId ?: FaceDb.MAIN_GROUP) == gid } },
            onDismiss = { manageGroups = false },
            onSave = { onEditGroups(it); manageGroups = false },
        )
    }
    if (modelMissing) {
        EmptyHint(
            "Für die Personen-Erkennung fehlt das Gesichtsmodell.\n\n" +
                "Lege eine Datei face_model.onnx (InsightFace w600k_mbf) oder face_model.tflite " +
                "in den Projektordner app/src/main/assets/ und baue die App neu."
        )
        return
    }
    val byGroup = remember(persons) { persons.filter { it.name != null }.groupBy { it.groupId ?: FaceDb.MAIN_GROUP } }
    val unnamed = remember(persons) { persons.filter { it.name == null } }
    val byId = remember(persons) { persons.associateBy { it.id } }

    // ---- Drag & Drop innerhalb einer Gruppe (Schlüssel "p_<gruppe>_<person>") ----
    val dragOrder = remember { mutableStateListOf<Int>() }
    var dragGroup by remember { mutableStateOf<Int?>(null) }
    var draggingId by remember { mutableStateOf<Int?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    val currentByGroup by rememberUpdatedState(byGroup)
    val currentOnReorder by rememberUpdatedState(onReorder)

    fun parseKey(key: Any): Pair<Int, Int>? {
        val k = key as? String ?: return null
        if (!k.startsWith("p_")) return null
        val parts = k.split('_')
        val g = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val p = parts.getOrNull(2)?.toIntOrNull() ?: return null
        return g to p
    }

    fun membersShown(gid: Int): List<PersonGroup> {
        val list = byGroup[gid].orEmpty()
        return if (draggingId != null && dragGroup == gid) dragOrder.mapNotNull { byId[it] } else list
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        state = state,
        userScrollEnabled = draggingId == null,
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .pointerInput(Unit) {
                fun hitAt(pos: Offset) = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    parseKey(info.key) != null &&
                        pos.x >= info.offset.x && pos.x < info.offset.x + info.size.width &&
                        pos.y >= info.offset.y && pos.y < info.offset.y + info.size.height
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val hit = hitAt(pos) ?: return@detectDragGesturesAfterLongPress
                        val (gid, pid) = parseKey(hit.key) ?: return@detectDragGesturesAfterLongPress
                        dragOrder.clear()
                        dragOrder.addAll(currentByGroup[gid].orEmpty().map { it.id })
                        dragGroup = gid
                        draggingId = pid
                        pointer = pos
                        grab = pos - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                    },
                    onDrag = { change, amount ->
                        val id = draggingId ?: return@detectDragGesturesAfterLongPress
                        change.consume()
                        pointer += amount
                        val over = hitAt(pointer)?.let { parseKey(it.key) } ?: return@detectDragGesturesAfterLongPress
                        if (over.first == dragGroup && over.second != id) {
                            val from = dragOrder.indexOf(id)
                            val to = dragOrder.indexOf(over.second)
                            if (from >= 0 && to >= 0) {
                                dragOrder.removeAt(from)
                                dragOrder.add(to, id)
                            }
                        }
                    },
                    onDragEnd = {
                        if (draggingId != null) currentOnReorder(dragOrder.toList())
                        draggingId = null; dragGroup = null
                    },
                    onDragCancel = { draggingId = null; dragGroup = null },
                )
            },
    ) {
        item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                if (busy) {
                    Text("Vorschläge werden aktualisiert …", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (modelLabel.isNotEmpty()) "Modell: $modelLabel" else "",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { manageGroups = true }) { Text("Gruppen bearbeiten") }
                    }
                }
            }
        }

        for ((index, g) in groups.withIndex()) {
            val members = membersShown(g.id)
            val isMain = g.id == FaceDb.MAIN_GROUP
            val open = isMain || g.id in openGroups
            item(key = "h_g${g.id}", span = { GridItemSpan(maxLineSpan) }) {
                if (isMain) {
                    SectionHeader(
                        "${g.name} (${members.size})",
                        hint = if (members.size > 1) "lange drücken und ziehen zum Sortieren" else null,
                    )
                } else {
                    CollapsibleHeader(
                        "${g.name} (${members.size})", open, divider = index > 0,
                        onLongPress = { manageGroups = true },
                    ) { onGroupOpen(g.id, !open) }
                }
            }
            if (isMain && members.isEmpty()) {
                item(key = "main_empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Gib einer Person einen Namen, damit sie hier erscheint.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (open) {
                items(members, key = { "p_${g.id}_${it.id}" }) { p ->
                    val dragging = p.id == draggingId
                    val key = "p_${g.id}_${p.id}"
                    PersonTile(
                        p,
                        onClick = { onOpen(p) },
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                if (dragging) {
                                    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                                    if (info != null) {
                                        translationX = pointer.x - grab.x - info.offset.x
                                        translationY = pointer.y - grab.y - info.offset.y
                                    }
                                    scaleX = 1.1f; scaleY = 1.1f
                                    shadowElevation = 12f
                                }
                            }
                            .then(if (dragging) Modifier else Modifier.animateItem()),
                    )
                }
            }
        }

        // Ohne Namen (aufklappbar)
        item(key = "h_unnamed", span = { GridItemSpan(maxLineSpan) }) {
            CollapsibleHeader("Ohne Namen (${unnamed.size})", showUnnamed, divider = true) { onShowUnnamed(!showUnnamed) }
        }
        if (showUnnamed) {
            if (unnamed.isEmpty()) {
                item(key = "unnamed_empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Noch keine weiteren Personen gefunden.\nDie Bilder werden im Hintergrund nach Gesichtern durchsucht.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(unnamed, key = { "u_${it.id}" }) { p -> PersonTile(p, onClick = { onOpen(p) }) }
        }
    }
}

/** Trennlinie + Titel + Pfeil zum Auf-/Zuklappen. Langer Druck: bearbeiten. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsibleHeader(
    title: String,
    open: Boolean,
    divider: Boolean,
    onLongPress: (() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = if (divider) 12.dp else 0.dp)) {
        if (divider) HorizontalDivider(thickness = 2.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (open) "Zuklappen" else "Aufklappen",
            )
        }
    }
}

/** Gruppen verwalten: Reihenfolge (Pfeile), Namen, löschen, neu anlegen. Übernahme erst mit "Speichern". */
@Composable
private fun GroupManagerDialog(
    groups: List<GroupDef>,
    countOf: (Int) -> Int,
    onDismiss: () -> Unit,
    onSave: (List<GroupDef>) -> Unit,
) {
    val list = remember { mutableStateListOf<GroupDef>().apply { addAll(groups) } }
    var nextTempId by remember { mutableIntStateOf(-1) }
    var newName by remember { mutableStateOf("") }
    // Drag & Drop
    var draggingId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gruppen bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState(), enabled = draggingId == null)) {
                Text(
                    "Am Griff ≡ ziehen zum Sortieren.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                list.forEachIndexed { i, g ->
                    key(g.id) {
                        val dragging = draggingId == g.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { heights[g.id] = it.size.height }
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (dragging) dragOffset else 0f
                                    if (dragging) { shadowElevation = 8f; scaleX = 1.02f; scaleY = 1.02f }
                                }
                                .background(
                                    if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Griff: anfassen und ziehen
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Ziehen zum Sortieren",
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(8.dp)
                                    .pointerInput(g.id) {
                                        detectDragGestures(
                                            onDragStart = { draggingId = g.id; dragOffset = 0f },
                                            onDragEnd = { draggingId = null; dragOffset = 0f },
                                            onDragCancel = { draggingId = null; dragOffset = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val idx = list.indexOfFirst { it.id == g.id }
                                                if (idx < 0) return@detectDragGestures
                                                if (dragOffset > 0 && idx < list.lastIndex) {
                                                    val h = heights[list[idx + 1].id] ?: return@detectDragGestures
                                                    if (dragOffset > h / 2f) {
                                                        list.add(idx + 1, list.removeAt(idx))
                                                        dragOffset -= h
                                                    }
                                                } else if (dragOffset < 0 && idx > 0) {
                                                    val h = heights[list[idx - 1].id] ?: return@detectDragGestures
                                                    if (-dragOffset > h / 2f) {
                                                        list.add(idx - 1, list.removeAt(idx))
                                                        dragOffset += h
                                                    }
                                                }
                                            },
                                        )
                                    },
                            )
                            OutlinedTextField(
                                value = g.name,
                                onValueChange = { v -> list.indexOfFirst { it.id == g.id }.takeIf { it >= 0 }?.let { list[it] = g.copy(name = v) } },
                                singleLine = true,
                                supportingText = {
                                    val n = if (g.id > 0) countOf(g.id) else 0
                                    Text(if (g.id == FaceDb.MAIN_GROUP) "$n Personen · immer sichtbar" else "$n Personen")
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (g.id != FaceDb.MAIN_GROUP) {
                                IconButton(onClick = { list.removeAll { it.id == g.id } }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Gruppe löschen")
                                }
                            } else {
                                Spacer(Modifier.size(48.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("Neue Gruppe …") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        list.add(GroupDef(nextTempId, newName.trim()))
                        nextTempId--
                        newName = ""
                    }, enabled = newName.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Gruppe hinzufügen")
                    }
                }
                Text(
                    "Gelöschte Gruppen: ihre Personen kommen nach „Haupt“.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(list.toList()) }, enabled = list.all { it.name.isNotBlank() }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Gruppe für eine Person wählen, inkl. neue Gruppe anlegen. */
@Composable
fun GroupPickerDialog(
    groups: List<GroupDef>,
    current: Int?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onPick: (GroupDef) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gruppe wählen") },
        text = {
            Column {
                for (g in groups) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(g) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = g.id == current, onClick = { onPick(g) })
                        Text(g.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("Neue Gruppe …") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onCreate(newName) }, enabled = newName.isNotBlank()) { Text("Anlegen") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun PersonTile(p: PersonGroup, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(6.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FaceAvatar(p.coverImage, p.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            p.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontStyle = if (p.name == null) FontStyle.Italic else FontStyle.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text("${p.images.size} Bilder", style = MaterialTheme.typography.bodySmall)
        if (p.suggestions.isNotEmpty()) {
            Text(
                "${p.suggestions.size} zu prüfen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun RenameDialog(current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name der Person") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun MergeDialog(others: List<PersonGroup>, onDismiss: () -> Unit, onPick: (PersonGroup) -> Unit) =
    PersonPickerDialog("Zusammenführen mit …", others, onDismiss = onDismiss, onPick = onPick)

/** Auswahl einer Person (mit Suchfeld). Benannte zuerst. */
@Composable
fun PersonPickerDialog(
    title: String,
    persons: List<PersonGroup>,
    onDismiss: () -> Unit,
    /** Bekommt den Suchtext (evtl. leer) als Vorschlag für den Namen */
    onCreateNew: ((String) -> Unit)? = null,
    onPick: (PersonGroup) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(persons, query) {
        val q = query.trim()
        if (q.isEmpty()) persons else persons.filter { it.name?.contains(q, ignoreCase = true) == true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Name suchen …") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (onCreateNew != null) {
                        item(key = "new") {
                            Row(
                                Modifier.fillMaxWidth().clickable { onCreateNew(query.trim()) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (query.isNotBlank()) "Neue Person „${query.trim()}“ erstellen" else "Neue Person erstellen …",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    items(filtered, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FaceAvatar(p.coverImage, p.cover, Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    p.displayName +
                                        if (p.groupId != null && p.groupId != FaceDb.MAIN_GROUP) " · ${p.groupName}" else "",
                                    fontStyle = if (p.name == null) FontStyle.Italic else FontStyle.Normal,
                                )
                                Text("${p.images.size} Bilder", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
