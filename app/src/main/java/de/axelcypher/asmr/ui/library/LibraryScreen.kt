package de.axelcypher.asmr.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.UpdateChannel
import de.axelcypher.asmr.playback.PlaybackEngine
import de.axelcypher.asmr.ui.formatDuration
import de.axelcypher.asmr.ui.imports.ImportDialog
import de.axelcypher.asmr.ui.imports.ImportsSheet
import de.axelcypher.asmr.ui.player.MiniPlayer
import de.axelcypher.asmr.ui.player.PlayerScreen
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, engine: PlaybackEngine, sharedUrl: MutableStateFlow<String?>) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channel by viewModel.updateChannel.collectAsStateWithLifecycle(initialValue = UpdateChannel.STABLE)
    val shared by sharedUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showImports by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.intents.collect { context.startActivity(it) } }

    // Benachrichtigung für Lockscreen-Steuerung; ohne Erlaubnis spielt es trotzdem.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun play(index: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        engine.play(state.items, index)
        showPlayer = true
    }

    if (showPlayer) {
        PlayerScreen(engine, state.serverUrl, onClose = { showPlayer = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bibliothek") },
                actions = {
                    TextButton(onClick = { showImport = true }) { Text("+ Import") }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Importe" + if (state.runningImports > 0) " (${state.runningImports} laufen)" else "") },
                                onClick = { menuOpen = false; showImports = true; viewModel.refreshImports() },
                            )
                            DropdownMenuItem(text = { Text("Aktualisieren") }, onClick = { menuOpen = false; viewModel.reload() })
                            DropdownMenuItem(
                                text = { Text("Update-Kanal: " + if (channel == UpdateChannel.STABLE) "Stabil" else "Nightly") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.setUpdateChannel(
                                        if (channel == UpdateChannel.STABLE) UpdateChannel.NIGHTLY else UpdateChannel.STABLE,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Nach Updates suchen") },
                                onClick = { menuOpen = false; viewModel.checkForUpdate(manual = true) },
                            )
                            DropdownMenuItem(text = { Text("Abmelden") }, onClick = { menuOpen = false; viewModel.logout() })
                        }
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(engine, onOpen = { showPlayer = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.update?.let { update ->
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Update verfügbar: ${update.versionName}", modifier = Modifier.weight(1f))
                        if (state.isDownloadingUpdate) {
                            CircularProgressIndicator(Modifier.padding(4.dp))
                        } else {
                            Button(onClick = viewModel::installUpdate) { Text("Installieren") }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                val error = state.error
                when {
                    state.items.isEmpty() && state.isLoading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.items.isEmpty() && error != null ->
                        ErrorMessage(error, onRetry = viewModel::reload, Modifier.align(Alignment.Center))

                    state.items.isEmpty() -> Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Noch keine Inhalte")
                        Text(
                            "Über \"+ Import\" eine URL einfügen oder in der YouTube-App \"Teilen\" → ASMR Player.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> ItemGrid(state, onPlay = ::play, onAmbient = engine::setAmbient, onLoadMore = viewModel::loadMore)
                }
            }
        }
    }

    if (showImport || shared != null) {
        ImportDialog(
            initialUrl = shared.orEmpty(),
            onImport = {
                viewModel.startImport(it)
                showImport = false
                sharedUrl.value = null
            },
            onDismiss = {
                showImport = false
                sharedUrl.value = null
            },
        )
    }
    if (showImports) ImportsSheet(state.imports, onDismiss = { showImports = false })
}

@Composable
private fun ItemGrid(
    state: LibraryUiState,
    onPlay: (Int) -> Unit,
    onAmbient: (ItemDto) -> Unit,
    onLoadMore: () -> Unit,
) {
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
            ItemCard(item, state.serverUrl, onPlay = { onPlay(index) }, onAmbient = { onAmbient(item) })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(item: ItemDto, serverUrl: String, onPlay: () -> Unit, onAmbient: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.combinedClickable(onClick = onPlay, onLongClick = { menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            val coverModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
            if (item.hasCover) {
                AsyncImage(
                    model = AsmrClient.coverUrl(serverUrl, item.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = coverModifier,
                )
            } else {
                Box(coverModifier)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Abspielen") }, onClick = { menuOpen = false; onPlay() })
                DropdownMenuItem(text = { Text("Als Ambient-Spur") }, onClick = { menuOpen = false; onAmbient() })
            }
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
