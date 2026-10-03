package de.axelcypher.asmr.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.ItemSort
import de.axelcypher.asmr.api.TagDto
import de.axelcypher.asmr.ui.AppIcons

@Composable
fun SearchBar(search: SearchFilters, onQuery: (String) -> Unit, onClear: () -> Unit, onFilters: () -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = search.query,
        onValueChange = onQuery,
        placeholder = { Text("Tracks, ASMRtists suchen") },
        leadingIcon = { Icon(AppIcons.Search, null) },
        trailingIcon = {
            Row {
                if (search.isActive) IconButton(onClick = onClear) { Icon(AppIcons.Close, "Suche leeren") }
                IconButton(onClick = onFilters) {
                    BadgedBox(badge = { if (search.filterCount > 0) Badge { Text("${search.filterCount}") } }) {
                        Icon(AppIcons.Tune, "Filter")
                    }
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Filter: Trigger (alle müssen passen), Creator, Favoriten, Länge, Sortierung. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    search: SearchFilters,
    tags: List<TagDto>,
    creators: List<String>,
    onChange: ((SearchFilters) -> SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange { SearchFilters(query = it.query) } }) { Text("Zurücksetzen") }
            }

            ChipGroup("Sortierung") {
                listOf(ItemSort.TITLE to "Titel", ItemSort.ADDED to "Neueste", ItemSort.RANDOM to "Zufällig").forEach { (sort, label) ->
                    FilterChip(selected = search.sort == sort, onClick = { onChange { it.copy(sort = sort) } }, label = { Text(label) })
                }
            }

            ChipGroup("Allgemein") {
                FilterChip(
                    selected = search.favoritesOnly,
                    onClick = { onChange { it.copy(favoritesOnly = !it.favoritesOnly) } },
                    label = { Text("Nur Favoriten") },
                    leadingIcon = { Icon(AppIcons.Heart, null) },
                )
            }

            ChipGroup("Länge") {
                LengthFilter.entries.forEach { length ->
                    FilterChip(selected = search.length == length, onClick = { onChange { it.copy(length = length) } }, label = { Text(length.label) })
                }
            }

            if (creators.isNotEmpty()) {
                ChipGroup("ASMRtist") {
                    creators.forEach { name ->
                        FilterChip(
                            selected = search.creator == name,
                            onClick = { onChange { it.copy(creator = if (it.creator == name) null else name) } },
                            label = { Text(name) },
                        )
                    }
                }
            }

            ChipGroup("Trigger (alle müssen vorkommen)") {
                if (tags.isEmpty()) Text("Lädt …", style = MaterialTheme.typography.bodySmall)
                tags.forEach { tag ->
                    val selected = search.tags.any { it.equals(tag.name, ignoreCase = true) }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onChange {
                                it.copy(tags = if (selected) it.tags.filterNot { t -> t.equals(tag.name, true) }.toSet() else it.tags + tag.name)
                            }
                        },
                        label = { Text("${tag.name} (${tag.count})") },
                    )
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("Fertig") }
        }
    }
}

@Composable
private fun ChipGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun SearchResults(
    results: List<ItemDto>,
    isSearching: Boolean,
    context: CardContext,
    onPlay: (List<ItemDto>, Int) -> Unit,
    onItemAction: (ItemDto, ItemAction) -> Unit,
) {
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
            if (isSearching) CircularProgressIndicator() else Text("Keine Treffer", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${results.size} Treffer", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onPlay(listOf(results.random()), 0) }) {
                    Icon(AppIcons.Shuffle, null)
                    Text("Zufällig", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = { onPlay(results, 0) }) { Text("Alle abspielen") }
            }
        }
        itemsIndexed(results, key = { _, item -> item.id }) { index, item ->
            TrackCard(item, context, onClick = { onPlay(results, index) }, onAction = { onItemAction(item, it) })
        }
    }
}
