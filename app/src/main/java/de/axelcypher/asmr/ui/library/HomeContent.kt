package de.axelcypher.asmr.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.axelcypher.asmr.api.CategoryDto
import de.axelcypher.asmr.api.HomeDto
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.PlaylistDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.ui.AppIcons

/** Rückrufe der Startseite; Tracks laufen über dieselben [ItemAction]s wie überall. */
class HomeCallbacks(
    val onCreator: (String) -> Unit,
    val onPlay: (List<ItemDto>, Int) -> Unit,
    val onAmbientTap: (ItemDto) -> Unit,
    val onItemAction: (ItemDto, ItemAction) -> Unit,
    val onSeeAll: (Detail) -> Unit,
    val onPlaylist: (PlaylistDto) -> Unit,
    val onCategory: (CategoryDto) -> Unit,
    val onCategoryEdit: (CategoryDto?) -> Unit,
)

@Composable
fun HomeContent(home: HomeDto?, context: CardContext, callbacks: HomeCallbacks, footer: @Composable () -> Unit = {}) {
    if (home == null) return
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (home.creators.isNotEmpty()) {
            item { SectionTitle("Creators") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(home.creators, key = { it.name }) { creator ->
                        Column(
                            Modifier.width(84.dp).clickable { callbacks.onCreator(creator.name) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Cover(
                                url = if (creator.hasAvatar) {
                                    "${AsmrClient.avatarUrl(context.serverUrl, creator.name)}?v=${context.imageVersion}"
                                } else {
                                    null
                                },
                                placeholder = AppIcons.Person,
                                modifier = Modifier.size(84.dp),
                                shape = CircleShape,
                            )
                            Text(
                                creator.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle("Favoriten") { callbacks.onSeeAll(Detail.Favorites) } }
        item {
            TrackRow(
                home.favorites, context, callbacks.onItemAction,
                empty = "Herz im Player oder Track-Menü setzt Favoriten.",
            ) { index -> callbacks.onPlay(home.favorites, index) }
        }

        item { SectionTitle("Playlists") { callbacks.onSeeAll(Detail.Playlists) } }
        item {
            if (home.playlists.isEmpty()) {
                EmptyHint("Track-Menü → \"Zu Playlist hinzufügen\" legt die erste Playlist an.")
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(home.playlists, key = { it.id }) { playlist ->
                        PlaylistCard(playlist, context, Modifier.width(150.dp)) { callbacks.onPlaylist(playlist) }
                    }
                }
            }
        }

        item { SectionTitle("Ambiente") { callbacks.onSeeAll(Detail.Ambient) } }
        item {
            TrackRow(
                home.ambient, context, callbacks.onItemAction,
                empty = "Tracks oder Ordner als Ambiente markieren (Admin).",
                showDetails = false,
            ) { index -> callbacks.onAmbientTap(home.ambient[index]) }
        }

        item {
            SectionTitle("Kategorien", action = if (context.isAdmin) "+ Neu" else null) { callbacks.onCategoryEdit(null) }
        }
        item {
            if (home.categories.isEmpty()) {
                EmptyHint(if (context.isAdmin) "Noch keine Kategorien. \"+ Neu\" legt eine an." else "Noch keine Kategorien.")
            } else {
                CategoryGrid(home.categories, context.isAdmin, callbacks)
            }
        }
        item { footer() }
    }
}

@Composable
private fun SectionTitle(title: String, action: String? = "Alle anzeigen", onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onAction != null && action != null) {
            TextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun TrackRow(
    items: List<ItemDto>,
    context: CardContext,
    onAction: (ItemDto, ItemAction) -> Unit,
    empty: String,
    showDetails: Boolean = true,
    onClick: (Int) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyHint(empty)
        return
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items.size, key = { items[it].id }) { index ->
            TrackCard(
                item = items[index],
                context = context,
                onClick = { onClick(index) },
                onAction = { onAction(items[index], it) },
                modifier = Modifier.width(120.dp),
                showDetails = showDetails,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(playlist: PlaylistDto, context: CardContext, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Cover(
                url = playlist.coverItemId?.let { "${AsmrClient.coverUrl(context.serverUrl, it)}?v=${context.imageVersion}" },
                placeholder = AppIcons.Library,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Badge("${playlist.itemCount}", Modifier.align(Alignment.BottomEnd))
        }
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (playlist.isMine) (if (playlist.shared) "Geteilt" else "Privat") else "von ${playlist.ownerName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryGrid(categories: List<CategoryDto>, isAdmin: Boolean, callbacks: HomeCallbacks) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        categories.chunked(CATEGORY_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { category ->
                    // Drei Spalten sind schmal: Icon oben, Name und Anzahl darunter.
                    Column(
                        Modifier
                            .weight(1f)
                            .height(92.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(parseColor(category.color))
                            .combinedClickable(
                                onClick = { callbacks.onCategory(category) },
                                onLongClick = { if (isAdmin) callbacks.onCategoryEdit(category) },
                            )
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(AppIcons.category(category.icon), contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
                        Column {
                            Text(
                                category.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${category.itemCount} Inhalte",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                repeat(CATEGORY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val CATEGORY_COLUMNS = 3

fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF2F4A48))
