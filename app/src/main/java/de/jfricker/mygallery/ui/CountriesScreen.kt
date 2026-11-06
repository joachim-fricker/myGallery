package de.jfricker.mygallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.jfricker.mygallery.data.CountryGroup

@Composable
fun CountriesScreen(
    countries: List<CountryGroup>,
    withoutGps: Int,
    state: LazyListState,
    onOpen: (CountryGroup) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(countries, query) {
        val q = query.trim()
        if (q.isEmpty()) countries
        else countries.filter { it.name.contains(q, ignoreCase = true) || it.code.equals(q, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Land suchen …") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Löschen")
                }
            },
            singleLine = true,
        )
        if (countries.isEmpty()) {
            EmptyHint("Noch keine Länder gefunden.\nDie GPS-Daten der Bilder werden im Hintergrund ausgewertet.")
            return@Column
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.code }) { c ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(c) },
                    leadingContent = { Text(c.flag, fontSize = 34.sp) },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text("${c.images.size} Bilder") },
                    trailingContent = {
                        Thumbnail(c.images.first(), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
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
