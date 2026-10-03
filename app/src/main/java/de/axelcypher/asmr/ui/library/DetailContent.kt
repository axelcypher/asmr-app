package de.axelcypher.asmr.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.PlaylistDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.ui.AppIcons

class DetailCallbacks(
    val onPlay: (List<ItemDto>, Int) -> Unit,
    val onAmbientTap: (ItemDto) -> Unit,
    val onItemAction: (ItemDto, ItemAction) -> Unit,
    val onCreatorProfile: (String) -> Unit,
    val onCategoryEdit: (de.axelcypher.asmr.api.CategoryDto) -> Unit,
    val onPlaylist: (PlaylistDto) -> Unit,
    val onNewPlaylist: () -> Unit,
    val onRenamePlaylist: (PlaylistDto) -> Unit,
    val onToggleShare: (PlaylistDto) -> Unit,
    val onDeletePlaylist: (PlaylistDto) -> Unit,
)

fun Detail.title(state: LibraryUiState): String = when (this) {
    is Detail.Creator -> name
    is Detail.Category -> category.name
    is Detail.Playlist -> state.detailPlaylist?.name ?: "Playlist"
    Detail.Favorites -> "Favoriten"
    Detail.Ambient -> "Ambiente"
    Detail.Playlists -> "Playlists"
}

@Composable
fun DetailContent(detail: Detail, state: LibraryUiState, context: CardContext, callbacks: DetailCallbacks) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { DetailHeader(detail, state, context, callbacks) }

        if (detail == Detail.Playlists) {
            items(state.playlists, key = { it.id }) { playlist ->
                PlaylistCard(playlist, context) { callbacks.onPlaylist(playlist) }
            }
        } else {
            val ambient = detail == Detail.Ambient || (detail is Detail.Category && detail.category.isAmbient)
            val ownPlaylist = state.detailPlaylist?.isMine == true
            itemsIndexed(state.detailItems, key = { _, item -> item.id }) { index, item ->
                TrackCard(
                    item = item,
                    context = context.copy(inOwnPlaylist = ownPlaylist),
                    onClick = { if (ambient) callbacks.onAmbientTap(item) else callbacks.onPlay(state.detailItems, index) },
                    onAction = { callbacks.onItemAction(item, it) },
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(detail: Detail, state: LibraryUiState, context: CardContext, callbacks: DetailCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (detail) {
            is Detail.Creator -> Row(verticalAlignment = Alignment.CenterVertically) {
                val hasAvatar = state.home?.creators?.firstOrNull { it.name == detail.name }?.hasAvatar == true
                Cover(
                    url = if (hasAvatar) "${AsmrClient.avatarUrl(context.serverUrl, detail.name)}?v=${context.imageVersion}" else null,
                    placeholder = AppIcons.Person,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text("${state.detailItems.size} Tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { callbacks.onCreatorProfile(detail.name) }) { Text("Profil & Links") }
                }
            }

            is Detail.Category -> Row(verticalAlignment = Alignment.CenterVertically) {
                // Aus der Übersicht nachschlagen: nach dem Bearbeiten ist das neue Bild sofort da.
                val category = state.home?.ambientCategories?.firstOrNull { it.id == detail.category.id } ?: detail.category
                if (category.isAmbient) {
                    CategoryImage(category, context, Modifier.size(72.dp))
                } else {
                    Icon(
                        AppIcons.category(category.icon), null,
                        tint = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(parseColor(category.color))
                            .padding(12.dp),
                    )
                }
                Text(
                    "${state.detailItems.size} Inhalte",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
                if (context.isAdmin) OutlinedButton(onClick = { callbacks.onCategoryEdit(category) }) { Text("Bearbeiten") }
            }

            is Detail.Playlist -> state.detailPlaylist?.let { playlist ->
                Text(
                    if (playlist.isMine) (if (playlist.shared) "Geteilt mit allen" else "Privat") else "von ${playlist.ownerName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (playlist.isMine) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { callbacks.onToggleShare(playlist) }) {
                            Text(if (playlist.shared) "Nicht mehr teilen" else "Teilen")
                        }
                        OutlinedButton(onClick = { callbacks.onRenamePlaylist(playlist) }) { Text("Umbenennen") }
                        OutlinedButton(onClick = { callbacks.onDeletePlaylist(playlist) }) { Text("Löschen") }
                    }
                }
            }

            Detail.Ambient -> Text(
                "Antippen legt den Track als zweite Spur unter den laufenden Titel; läuft nichts, spielt er im Loop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Detail.Playlists -> Button(onClick = callbacks.onNewPlaylist) { Text("Neue Playlist") }

            Detail.Favorites -> Unit
        }

        val ambientCategory = detail is Detail.Category && detail.category.isAmbient
        if (ambientCategory) {
            Text(
                "Ambiente: Antippen legt den Sound als zweite Spur unter den laufenden Titel; läuft nichts, spielt er im Loop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (detail != Detail.Playlists && detail != Detail.Ambient && !ambientCategory && state.detailItems.isNotEmpty()) {
            Button(onClick = { callbacks.onPlay(state.detailItems, 0) }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Play, null)
                Text("Alle abspielen", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
