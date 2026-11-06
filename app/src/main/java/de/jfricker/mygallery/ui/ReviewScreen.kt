package de.jfricker.mygallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.jfricker.mygallery.data.FaceRef
import de.jfricker.mygallery.data.FaceStatus
import de.jfricker.mygallery.data.MediaImage

private const val BATCH = 40

/**
 * "Ist das <Name>?" – Gesichter einer Person in Runden prüfen.
 * Falsche antippen, dann bestätigen: der Rest gilt als richtig.
 * allMode = false: Vorschläge (wahrscheinlichste zuerst)
 * allMode = true:  automatisch zugeordnete (unsicherste zuerst) – zum Aufräumen
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(
    person: PersonGroup,
    allMode: Boolean,
    onSubmit: (confirm: List<Long>, reject: List<Long>) -> Unit,
    onOpenImage: (MediaImage) -> Unit,
    onBack: () -> Unit,
) {
    val handled = remember { mutableStateListOf<Long>() }
    var round by remember { mutableIntStateOf(0) }

    fun source(): List<Pair<MediaImage, FaceRef>> =
        if (allMode) person.faces.filter { it.second.status == FaceStatus.AUTO }.sortedBy { it.second.score }
        else person.suggestions.sortedByDescending { it.second.score }

    // Runde einfrieren, damit sich nichts verschiebt, während du tippst
    val batch = remember(round) { source().filter { it.second.id !in handled }.take(BATCH) }
    val remaining = source().count { it.second.id !in handled }
    val rejected = remember(round) { mutableStateListOf<Long>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ist das ${person.displayName}?")
                        Text(
                            if (batch.isEmpty()) "fertig" else "Falsche antippen · noch $remaining",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        bottomBar = {
            if (batch.isNotEmpty()) {
                BottomAppBar {
                    Button(
                        onClick = {
                            val reject = rejected.toList()
                            val confirm = batch.map { it.second.id }.filter { it !in reject }
                            onSubmit(confirm, reject)
                            handled.addAll(batch.map { it.second.id })
                            round++
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                        Text("${batch.size - rejected.size} bestätigen · ${rejected.size} ablehnen")
                    }
                }
            }
        },
    ) { padding ->
        if (batch.isEmpty()) {
            EmptyHint(
                if (handled.isEmpty()) "Nichts zu prüfen." else "Alles geprüft.\nDie App lernt aus deinen Antworten und aktualisiert die Vorschläge.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxSize().padding(padding).padding(4.dp),
        ) {
            items(batch, key = { it.second.id }) { (img, face) ->
                val isRejected = face.id in rejected
                Box(
                    Modifier
                        .padding(3.dp)
                        .aspectRatio(1f)
                        .combinedClickable(
                            onClick = { if (isRejected) rejected.remove(face.id) else rejected.add(face.id) },
                            onLongClick = { onOpenImage(img) },
                        )
                ) {
                    FaceAvatar(img, face, Modifier.fillMaxSize(), RoundedCornerShape(10.dp))
                    if (isRejected) {
                        Box(Modifier.fillMaxSize().background(Color(0x99B00020), RoundedCornerShape(10.dp)))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "nicht ${person.displayName}",
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(48.dp),
                        )
                    }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    "Tipp: lange drücken zeigt das ganze Foto.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
    }
}
