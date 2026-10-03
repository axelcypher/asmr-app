package de.axelcypher.asmr.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import de.axelcypher.asmr.data.api.AbsClient
import de.axelcypher.asmr.data.api.AsmrItem
import de.axelcypher.asmr.ui.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { LibraryPicker(state, onSelect = viewModel::selectLibrary) },
                actions = { TextButton(onClick = viewModel::logout) { Text("Abmelden") } },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val error = state.error
            when {
                state.items.isEmpty() && state.isLoading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.items.isEmpty() && error != null ->
                    ErrorMessage(error, onRetry = viewModel::reload, Modifier.align(Alignment.Center))

                state.items.isEmpty() && state.libraries.isEmpty() ->
                    Text("Keine Bibliothek gefunden", Modifier.align(Alignment.Center))

                state.items.isEmpty() ->
                    Text("Diese Bibliothek ist leer", Modifier.align(Alignment.Center))

                else -> ItemGrid(state, onLoadMore = viewModel::loadMore)
            }
        }
    }
}

@Composable
private fun LibraryPicker(state: LibraryUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val title = state.selectedLibrary?.name ?: "Bibliothek"
    if (state.libraries.size <= 1) {
        Text(title)
        return
    }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("$title ▾", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.libraries.forEach { library ->
                DropdownMenuItem(
                    text = { Text(library.name) },
                    onClick = {
                        expanded = false
                        onSelect(library.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ItemGrid(state: LibraryUiState, onLoadMore: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
            if (index >= state.items.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(state.items.size) { onLoadMore() }
            }
            ItemCard(item, state.serverUrl)
        }
        val error = state.error
        if (state.isLoading || error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (error != null) ErrorMessage(error, onRetry = onLoadMore) else CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun ItemCard(item: AsmrItem, serverUrl: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
        if (item.hasCover) {
            AsyncImage(
                model = AbsClient.coverUrl(serverUrl, item.id),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = coverModifier,
            )
        } else {
            Box(coverModifier)
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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

@Composable
private fun ErrorMessage(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Erneut versuchen") }
    }
}

private const val LOAD_MORE_THRESHOLD = 6
