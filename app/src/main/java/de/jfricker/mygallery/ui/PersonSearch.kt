package de.jfricker.mygallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.jfricker.mygallery.data.FaceStatus
import de.jfricker.mygallery.data.FaceSnapshot
import de.jfricker.mygallery.data.MediaImage
import de.jfricker.mygallery.data.Countries
import de.jfricker.mygallery.data.yearOf
import java.text.Collator

/** Benannte Personen pro Bild (nur sichere Zuordnungen: bestätigt + automatisch). */
fun namedPersonsPerImage(images: List<MediaImage>, snap: FaceSnapshot): Map<Long, Set<Int>> {
    val out = HashMap<Long, Set<Int>>()
    for (img in images) {
        val ids = snap.facesByImage[img.id]
            ?.filter { it.personId in snap.names && (it.status == FaceStatus.AUTO || it.status == FaceStatus.CONFIRMED) }
            ?.mapTo(HashSet()) { it.personId }
        if (!ids.isNullOrEmpty()) out[img.id] = ids
    }
    return out
}

/**
 * Bilder, auf denen ALLE ausgewählten Personen vorkommen (Reihenfolge wie [images], also neueste zuerst).
 * [onlyThese]: zusätzlich keine weiteren benannten Personen auf dem Bild (unbekannte Gesichter stören nicht).
 */
fun searchByPersons(
    images: List<MediaImage>,
    perImage: Map<Long, Set<Int>>,
    selected: Set<Int>,
    onlyThese: Boolean,
): List<MediaImage> {
    if (selected.isEmpty()) return emptyList()
    return images.filter { img ->
        val s = perImage[img.id] ?: return@filter false
        s.containsAll(selected) && (!onlyThese || s.size == selected.size)
    }
}

/** Personen-Treffer, eingeschränkt auf Land/Jahr, plus die möglichen Länder/Jahre mit Anzahl. */
class PersonFilter(
    val results: List<MediaImage>,
    /** (ISO-Code, Anzahl) – nach Name sortiert; berücksichtigt das gewählte Jahr */
    val countries: List<Pair<String, Int>>,
    /** (Jahr, Anzahl) – neueste zuerst; berücksichtigt das gewählte Land */
    val years: List<Pair<Int, Int>>,
    /** Treffer ohne Land (kein GPS) – nur zur Info */
    val withoutCountry: Int,
)

fun personFilter(
    hits: List<MediaImage>,
    countryOf: (MediaImage) -> String?,
    country: String?,
    year: Int?,
): PersonFilter {
    val c = hits.associate { it.id to countryOf(it) }
    fun okCountry(img: MediaImage) = country == null || c[img.id] == country
    fun okYear(img: MediaImage) = year == null || yearOf(img.date) == year
    val collator = Collator.getInstance()
    val countries = hits.filter { okYear(it) }.mapNotNull { c[it.id] }
        .groupingBy { it }.eachCount().toList()
        .sortedWith { a, b -> collator.compare(Countries.name(a.first), Countries.name(b.first)) }
    val years = hits.filter { okCountry(it) }.groupingBy { yearOf(it.date) }.eachCount().toList()
        .sortedByDescending { it.first }
    val results = hits.filter { okCountry(it) && okYear(it) }
    return PersonFilter(results, countries, years, hits.count { okYear(it) && c[it.id] == null })
}

/**
 * Für die Auswahl: wie viele Bilder es gäbe, wenn Person p zur Auswahl dazukäme.
 * Ohne Auswahl = Anzahl Bilder der Person.
 */
private fun countsWith(perImage: Map<Long, Set<Int>>, selected: Set<Int>): Map<Int, Int> {
    val counts = HashMap<Int, Int>()
    for (s in perImage.values) {
        if (!s.containsAll(selected)) continue
        for (p in s) if (p !in selected) counts[p] = (counts[p] ?: 0) + 1
    }
    return counts
}

/**
 * Inhalt der Personen-Suche: ausgewählte Personen als Chips, Schalter „Nur diese Personen“, darunter die Treffer.
 * Ohne Auswahl wird direkt die Personen-Auswahl gezeigt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSearchContent(
    persons: List<PersonGroup>,
    perImage: Map<Long, Set<Int>>,
    selected: List<Int>,
    onToggle: (Int) -> Unit,
    onlyThese: Boolean,
    onOnlyThese: (Boolean) -> Unit,
    filter: PersonFilter,
    country: String?,
    onCountry: (String?) -> Unit,
    year: Int?,
    onYear: (Int?) -> Unit,
    gridState: LazyGridState,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val named = remember(persons) { persons.filter { it.name != null } }
    val byId = remember(named) { named.associateBy { it.id } }
    val selectedSet = remember(selected) { selected.toSet() }
    val counts = remember(perImage, selectedSet) { countsWith(perImage, selectedSet) }
    var picker by remember { mutableStateOf(false) }

    if (picker) {
        ModalBottomSheet(
            onDismissRequest = { picker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "Personen wählen",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            PersonPicker(named, selectedSet, counts, onToggle, Modifier.fillMaxWidth().padding(bottom = 16.dp))
        }
    }

    Column(modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(selected.mapNotNull { byId[it] }, key = { it.id }) { p ->
                InputChip(
                    selected = true,
                    onClick = { onToggle(p.id) },
                    label = { Text(p.displayName) },
                    avatar = { FaceAvatar(p.coverImage, p.cover, Modifier.size(24.dp)) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Entfernen", Modifier.size(18.dp)) },
                )
            }
            if (selected.isNotEmpty()) item(key = "add") {
                InputChip(
                    selected = false,
                    onClick = { picker = true },
                    label = { Text("Person") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
        if (selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable { onOnlyThese(!onlyThese) }.padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Nur diese Personen")
                    Text(
                        "keine weiteren benannten Personen auf dem Bild",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = onlyThese, onCheckedChange = onOnlyThese)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterDropdown(
                    label = country?.let { "${Countries.flag(it)} ${Countries.name(it)}" } ?: "Alle Länder",
                    active = country != null,
                    allLabel = "Alle Länder",
                    options = filter.countries.map { (code, n) -> code to "${Countries.flag(code)} ${Countries.name(code)} ($n)" },
                    footer = if (filter.withoutCountry > 0) "${filter.withoutCountry} Bilder ohne GPS-Position" else null,
                    onSelect = onCountry,
                )
                FilterDropdown(
                    label = year?.toString() ?: "Alle Jahre",
                    active = year != null,
                    allLabel = "Alle Jahre",
                    options = filter.years.map { (y, n) -> y to "$y ($n)" },
                    footer = null,
                    onSelect = onYear,
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                selected.isEmpty() -> PersonPicker(named, selectedSet, counts, onToggle, Modifier.fillMaxSize())
                filter.results.isEmpty() -> EmptyHint(
                    when {
                        country != null || year != null -> "Keine Treffer mit diesem Land / Jahr."
                        onlyThese -> "Keine Bilder mit genau diesen Personen."
                        else -> "Keine Bilder, auf denen alle diese Personen sind."
                    }
                )
                else -> ImageGrid(filter.results, onOpen = onOpen, state = gridState)
            }
        }
    }
}

/** Raster aller benannten Personen nach Gruppen; Zahl = Treffer, wenn man die Person dazunimmt. */
@Composable
private fun PersonPicker(
    named: List<PersonGroup>,
    selected: Set<Int>,
    counts: Map<Int, Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier,
) {
    if (named.isEmpty()) {
        EmptyHint("Noch keine benannten Personen.", modifier)
        return
    }
    val groups = remember(named) { named.groupBy { it.groupName ?: "Haupt" }.toList() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(88.dp),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
    ) {
        for ((groupName, members) in groups) {
            item(key = "g_$groupName", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    groupName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(members, key = { it.id }) { p ->
                val isSel = p.id in selected
                val count = if (selected.isEmpty()) p.images.size else counts[p.id] ?: 0
                val enabled = isSel || count > 0
                Column(
                    Modifier
                        .padding(4.dp)
                        .alpha(if (enabled) 1f else 0.35f)
                        .clickable(enabled = enabled) { onToggle(p.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FaceAvatar(
                        p.coverImage, p.cover,
                        Modifier
                            .size(64.dp)
                            .then(
                                if (isSel) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), CircleShape)
                                else Modifier
                            ),
                    )
                    Text(
                        p.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (isSel) "ausgewählt" else "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Chip mit Auswahlmenü („Alle …“ + Optionen mit Anzahl). */
@Composable
private fun <T> FilterDropdown(
    label: String,
    active: Boolean,
    allLabel: String,
    options: List<Pair<T, String>>,
    footer: String?,
    onSelect: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { open = false; onSelect(null) })
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
            footer?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
