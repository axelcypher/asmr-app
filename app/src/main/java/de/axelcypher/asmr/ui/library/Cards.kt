package de.axelcypher.asmr.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.playback.OfflineState
import de.axelcypher.asmr.ui.AppIcons
import de.axelcypher.asmr.ui.formatDuration

/** Alles, was ein Track-Menü anbieten kann. */
enum class ItemAction {
    Layer, Favorite, AddToPlaylist, RemoveFromPlaylist, Creator,
    Download, RemoveDownload, Edit, Categories, Move, Delete,
}

/** Was die Kacheln zum Anzeigen brauchen, unabhängig vom Bildschirm. */
data class CardContext(
    val serverUrl: String,
    val imageVersion: Int,
    val isAdmin: Boolean,
    val downloads: Map<Long, OfflineState>,
    /** Gesetzt in einer eigenen Playlist: dann gibt es "Aus Playlist entfernen". */
    val inOwnPlaylist: Boolean = false,
)

fun CardContext.coverUrl(item: ItemDto) =
    if (item.hasCover) "${AsmrClient.coverUrl(serverUrl, item.id)}?v=$imageVersion" else null

@Composable
fun Cover(url: String?, placeholder: ImageVector, modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(10.dp)) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Track-Kachel mit Cover, Dauer, Offline-Badge und Menü per langem Tippen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCard(
    item: ItemDto,
    context: CardContext,
    onClick: () -> Unit,
    onAction: (ItemAction) -> Unit,
    modifier: Modifier = Modifier,
    showDetails: Boolean = true,
    titleLines: Int = 2,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val download = context.downloads[item.id]
    Column(
        modifier.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Cover(context.coverUrl(item), AppIcons.Play, Modifier.fillMaxWidth())
            item.durationSeconds?.let {
                Badge(formatDuration(it), Modifier.align(Alignment.BottomEnd))
            }
            if (download != null) {
                Badge(if (download.downloaded) "✓ offline" else "${download.percent} %", Modifier.align(Alignment.BottomStart))
            }
            if (item.isFavorite) {
                Icon(
                    AppIcons.Heart, "Favorit",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ItemMenuEntries(item, context, download) {
                    menuOpen = false
                    onAction(it)
                }
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        if (showDetails) {
            item.creator?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    item.tags.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ItemMenuEntries(item: ItemDto, context: CardContext, download: OfflineState?, onAction: (ItemAction) -> Unit) {
    @Composable
    fun entry(label: String, action: ItemAction) = DropdownMenuItem(text = { Text(label) }, onClick = { onAction(action) })

    entry(if (item.isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten", ItemAction.Favorite)
    entry("Zu Playlist hinzufügen", ItemAction.AddToPlaylist)
    if (context.inOwnPlaylist) entry("Aus Playlist entfernen", ItemAction.RemoveFromPlaylist)
    entry("Als zweite Spur (Ambient)", ItemAction.Layer)
    entry(if (download == null) "Herunterladen" else "Download entfernen", if (download == null) ItemAction.Download else ItemAction.RemoveDownload)
    if (item.creator != null) entry("ASMRtist-Profil", ItemAction.Creator)
    if (context.isAdmin) {
        entry("Bearbeiten & bewerten", ItemAction.Edit)
        entry("Kategorien", ItemAction.Categories)
        entry("Verschieben", ItemAction.Move)
        entry("Löschen", ItemAction.Delete)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.75f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
