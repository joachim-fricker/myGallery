package de.jfricker.mygallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.jfricker.mygallery.data.Album
import de.jfricker.mygallery.data.YearAlbums

/**
 * Jahr (aufklappbar)
 *     alle Alben/Ordner dieses Jahres
 */
@Composable
fun AlbumsScreen(years: List<YearAlbums>, state: LazyGridState, onOpen: (Album) -> Unit) {
    if (years.isEmpty()) {
        EmptyHint("Keine Alben gefunden"); return
    }
    // Aufgeklappte Jahre; standardmässig alle offen
    val collapsed = remember { mutableStateMapOf<Int, Boolean>() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
    ) {
        for (y in years) {
            val open = collapsed[y.year] != true
            item(key = "y_${y.year}", span = { GridItemSpan(maxLineSpan) }, contentType = "year") {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { collapsed[y.year] = open }
                            .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (y.year > 0) y.year.toString() else "Ohne Datum",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${y.albums.size} Alben · ${y.imageCount} Bilder",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Icon(
                            if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (open) "Zuklappen" else "Aufklappen",
                        )
                    }
                    HorizontalDivider()
                }
            }
            if (open) {
                items(y.albums, key = { it.key }, contentType = { "album" }) { album ->
                    AlbumCard(album, Modifier.padding(start = 12.dp)) { onOpen(album) }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Thumbnail(
            album.images.first(),
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
        )
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Text(
            "${album.images.size} Bilder",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }
}
