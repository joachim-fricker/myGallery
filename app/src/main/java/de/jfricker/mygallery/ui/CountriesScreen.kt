package de.jfricker.mygallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.jfricker.mygallery.GalleryViewModel
import de.jfricker.mygallery.data.CountryGroup
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private fun day(ms: Long) = DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Zeile unter dem Landesnamen: Anzahl Bilder, Zeitraum bzw. "manuell eingetragen". */
fun countryInfo(c: CountryGroup): String = when {
    c.manual -> "Besucht · manuell eingetragen, keine Bilder mit GPS"
    else -> buildString {
        append("${c.images.size} Bilder · ${day(c.firstDate)} – ${day(c.lastDate)}")
        if (c.excluded) append(" · zählt nicht als besucht")
    }
}

/**
 * Länder: Liste (Flagge, Name, Zeitraum, Suche, Sortierung) oder Weltkarte mit allen besuchten Ländern.
 * Besucht = Land aus GPS der Bilder oder manuell eingetragen (data/VisitedCountries.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountriesScreen(
    countries: List<CountryGroup>,
    withoutGps: Int,
    withGps: Int,
    state: LazyListState,
    vm: GalleryViewModel,
    onOpen: (CountryGroup) -> Unit,
) {
    val visitedCount = countries.count { it.visited }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$visitedCount Länder besucht", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$withGps Bilder mit GPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !vm.countryShowMap,
                    onClick = { vm.countryShowMap = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Liste") }
                SegmentedButton(
                    selected = vm.countryShowMap,
                    onClick = { vm.countryShowMap = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Karte") }
            }
        }
        if (vm.countryShowMap) {
            CountryMapPane(countries, vm, onOpen)
        } else {
            CountryListPane(countries, withoutGps, state, vm, onOpen)
        }
    }
}

@Composable
private fun CountryListPane(
    countries: List<CountryGroup>,
    withoutGps: Int,
    state: LazyListState,
    vm: GalleryViewModel,
    onOpen: (CountryGroup) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val byImages = vm.countrySortByImages
    val shown = remember(countries, query, byImages) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) countries
        else countries.filter { it.name.contains(q, ignoreCase = true) || it.code.equals(q, ignoreCase = true) }
        if (byImages) {
            // meiste Bilder zuerst, manuell eingetragene am Ende (alphabetisch)
            val collator = Collator.getInstance()
            filtered.sortedWith(compareByDescending<CountryGroup> { it.images.size }.thenComparator { a, b -> collator.compare(a.name, b.name) })
        } else filtered
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Land suchen …") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Löschen")
                }
            },
            singleLine = true,
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !byImages, onClick = { vm.countrySortByImages = false }, label = { Text("A–Z") })
            FilterChip(selected = byImages, onClick = { vm.countrySortByImages = true }, label = { Text("Nach Bildern") })
        }
        if (countries.isEmpty()) {
            EmptyHint("Noch keine Länder gefunden.\nDie GPS-Daten der Bilder werden im Hintergrund ausgewertet.")
            return@Column
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.code }) { c ->
                val dim = if (c.excluded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                ListItem(
                    modifier = Modifier.clickable {
                        if (c.images.isNotEmpty()) onOpen(c)
                        else { vm.countrySelected = c.code; vm.countryFocus = c.code; vm.countryShowMap = true }
                    },
                    leadingContent = { Text(c.flag, fontSize = 34.sp) },
                    headlineContent = { Text(c.name, color = dim) },
                    supportingContent = { Text(countryInfo(c)) },
                    trailingContent = {
                        c.images.firstOrNull()?.let {
                            Thumbnail(it, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                        }
                    },
                )
            }
            if (withoutGps > 0 && query.isBlank()) {
                item(key = "nogps") {
                    Text(
                        "$withoutGps Bilder ohne GPS-Position",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryMapPane(
    countries: List<CountryGroup>,
    vm: GalleryViewModel,
    onOpen: (CountryGroup) -> Unit,
) {
    val byCode = remember(countries) { countries.associateBy { it.code } }
    val visited = remember(countries) { countries.filter { it.visited }.mapTo(HashSet()) { it.code } }
    Box(Modifier.fillMaxSize()) {
        CountryMap(visited, vm.countrySelected, vm) { vm.countrySelected = it }
        vm.countrySelected?.let { code ->
            CountryCard(
                code = code,
                group = byCode[code],
                fallbackName = vm.countryShapeName(code),
                onClose = { vm.countrySelected = null },
                onOpen = onOpen,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }
}

@Composable
private fun CountryCard(
    code: String,
    group: CountryGroup?,
    fallbackName: String?,
    onClose: () -> Unit,
    onOpen: (CountryGroup) -> Unit,
    modifier: Modifier,
) {
    val name = group?.name ?: fallbackName ?: code
    val flag = group?.flag ?: de.jfricker.mygallery.data.Countries.flag(code)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flag, fontSize = 30.sp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    if (group == null) "Noch nicht besucht" else countryInfo(group),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (group != null && group.images.isNotEmpty()) {
                TextButton(onClick = { onOpen(group) }) { Text("Bilder") }
            }
            TextButton(onClick = onClose) { Text("✕") }
        }
    }
}
