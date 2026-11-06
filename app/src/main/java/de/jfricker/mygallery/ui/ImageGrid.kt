package de.jfricker.mygallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jfricker.mygallery.data.MediaImage
import de.jfricker.mygallery.data.monthGroups

/** Bilderraster mit Monats-Überschriften. [onOpen] bekommt den Index in [images]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGrid(
    images: List<MediaImage>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((Int) -> Unit)? = null,
    /** Ausgewählte Bild-IDs (Mehrfachauswahl) – werden mit Häkchen markiert */
    selected: Set<Long> = emptySet(),
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (images.isEmpty()) {
        EmptyHint("Keine Bilder", modifier)
        return
    }
    val groups = remember(images) { monthGroups(images) }
    val indexOf = remember(images) {
        HashMap<Long, Int>(images.size * 2).apply { images.forEachIndexed { i, m -> put(m.id, i) } }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        for ((label, items) in groups) {
            item(key = "h_$label", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(items, key = { it.id }, contentType = { "img" }) { img ->
                val isSelected = img.id in selected
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .padding(1.dp)
                        .combinedClickable(
                            onClick = { onOpen(indexOf[img.id] ?: 0) },
                            onLongClick = onLongPress?.let { lp -> { lp(indexOf[img.id] ?: 0) } },
                        )
                ) {
                    Thumbnail(img, Modifier.fillMaxSize().padding(if (isSelected) 8.dp else 0.dp))
                    if (isSelected) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "ausgewählt",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .background(Color.White, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}
