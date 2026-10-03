package de.axelcypher.asmr.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.playback.OfflineStore
import de.axelcypher.asmr.ui.AppIcons
import de.axelcypher.asmr.ui.formatDuration

/** Heruntergeladene Tracks; funktioniert komplett ohne Server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    offline: OfflineStore,
    onPlay: (List<ItemDto>, Int) -> Unit,
    onAmbient: (ItemDto) -> Unit,
    onClose: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val items by offline.items.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heruntergeladen") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(AppIcons.ArrowBack, "Zurück") } },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Noch nichts heruntergeladen. In der Bibliothek einen Track oder Ordner lange antippen → Herunterladen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                DownloadedCard(item, offline, onPlay = { onPlay(items, index) }, onAmbient = { onAmbient(item) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedCard(item: ItemDto, offline: OfflineStore, onPlay: () -> Unit, onAmbient: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.combinedClickable(onClick = onPlay, onLongClick = { menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            offline.localCover(item.id)?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Als Ambient-Spur") }, onClick = { menuOpen = false; onAmbient() })
                DropdownMenuItem(text = { Text("Download entfernen") }, onClick = { menuOpen = false; offline.remove(item.id) })
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val details = listOfNotNull(item.creator, item.durationSeconds?.let(::formatDuration))
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
