package de.axelcypher.asmr.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.axelcypher.asmr.api.FolderDto
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.UpdateChannel
import de.axelcypher.asmr.playback.OfflineState
import de.axelcypher.asmr.playback.OfflineStore
import de.axelcypher.asmr.playback.PlaybackEngine
import de.axelcypher.asmr.ui.AppIcons
import de.axelcypher.asmr.ui.creator.CreatorSheet
import de.axelcypher.asmr.ui.formatDuration
import de.axelcypher.asmr.ui.imports.ImportDialog
import de.axelcypher.asmr.ui.imports.ImportsSheet
import de.axelcypher.asmr.ui.player.MiniPlayer
import de.axelcypher.asmr.ui.player.PlayerScreen
import de.axelcypher.asmr.ui.profile.ProfileScreen
import de.axelcypher.asmr.ui.profile.ProfileViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/** Was gerade als Dialog offen ist; nur eins gleichzeitig. */
private sealed interface LibraryDialog {
    data object Import : LibraryDialog
    data object Imports : LibraryDialog
    data object NewFolder : LibraryDialog
    data class Edit(val item: ItemDto) : LibraryDialog
    data class Move(val item: ItemDto) : LibraryDialog
    data class Delete(val item: ItemDto) : LibraryDialog
    data class Access(val folder: FolderDto) : LibraryDialog
    data class Creator(val name: String) : LibraryDialog
}

private enum class FolderAction { Access, Creator, Download }
private enum class ItemAction { Ambient, Creator, Edit, Move, Delete, Download, RemoveDownload }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    engine: PlaybackEngine,
    sharedUrl: MutableStateFlow<String?>,
    client: AsmrClient,
    offline: OfflineStore,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channel by viewModel.updateChannel.collectAsStateWithLifecycle(initialValue = UpdateChannel.STABLE)
    val shared by sharedUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    val downloads by offline.states.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(viewModel) { viewModel.intents.collect { context.startActivity(it) } }
    LaunchedEffect(shared) { if (shared != null) dialog = LibraryDialog.Import }

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
        PlayerScreen(
            engine,
            state.serverUrl,
            onClose = { showPlayer = false },
            onCreator = { dialog = LibraryDialog.Creator(it) },
        )
    } else if (showProfile) {
        ProfileScreen(
            viewModel { ProfileViewModel(client) },
            onClose = {
                showProfile = false
                viewModel.reload()
            },
        )
    } else if (showDownloads) {
        DownloadsScreen(
            offline = offline,
            onPlay = { items, index ->
                engine.play(items, index)
                showPlayer = true
            },
            onAmbient = engine::setAmbient,
            onClose = { showDownloads = false },
            bottomBar = { MiniPlayer(engine, onOpen = { showPlayer = true }) },
        )
    } else {
        BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.breadcrumbs.lastOrNull() ?: "Bibliothek", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (state.path.isNotEmpty()) {
                            IconButton(onClick = { viewModel.up() }) { Icon(AppIcons.ArrowBack, "Ordner hoch") }
                        }
                    },
                    actions = {
                        IconButton(onClick = { dialog = LibraryDialog.Import }) { Icon(AppIcons.Add, "Importieren") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(AppIcons.MoreVert, "Menü") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (state.isAdmin) {
                                    MenuEntry("Neuer Ordner") { menuOpen = false; dialog = LibraryDialog.NewFolder }
                                }
                                MenuEntry("Importe" + if (state.runningImports > 0) " (${state.runningImports} laufen)" else "") {
                                    menuOpen = false
                                    dialog = LibraryDialog.Imports
                                    viewModel.refreshImports()
                                }
                                MenuEntry("Heruntergeladen") { menuOpen = false; showDownloads = true }
                                MenuEntry("Aktualisieren") { menuOpen = false; viewModel.reload() }
                                MenuEntry("Update-Kanal: " + if (channel == UpdateChannel.STABLE) "Stabil" else "Nightly") {
                                    menuOpen = false
                                    viewModel.setUpdateChannel(
                                        if (channel == UpdateChannel.STABLE) UpdateChannel.NIGHTLY else UpdateChannel.STABLE,
                                    )
                                }
                                MenuEntry("Nach Updates suchen") { menuOpen = false; viewModel.checkForUpdate(manual = true) }
                                MenuEntry("Profil") { menuOpen = false; showProfile = true }
                            }
                        }
                    },
                )
            },
            bottomBar = { MiniPlayer(engine, onOpen = { showPlayer = true }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
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
                if (state.breadcrumbs.isNotEmpty()) Breadcrumbs(state.breadcrumbs, onOpen = viewModel::open)
                LibraryContent(
                    state = state,
                    viewModel = viewModel,
                    downloads = downloads,
                    onShowDownloads = { showDownloads = true },
                    onPlay = ::play,
                    onFolderAction = { folder, action ->
                        dialog = when (action) {
                            FolderAction.Access -> {
                                viewModel.loadAccess()
                                LibraryDialog.Access(folder)
                            }
                            FolderAction.Creator -> LibraryDialog.Creator(folder.name)
                            FolderAction.Download -> {
                                viewModel.downloadFolder(folder.path)
                                null
                            }
                        }
                    },
                    onItemAction = { item, action ->
                        when (action) {
                            ItemAction.Ambient -> engine.setAmbient(item)
                            ItemAction.Download -> viewModel.download(item)
                            ItemAction.RemoveDownload -> viewModel.removeDownload(item)
                            ItemAction.Creator -> dialog = item.creator?.let(LibraryDialog::Creator)
                            ItemAction.Edit -> dialog = LibraryDialog.Edit(item)
                            ItemAction.Move -> {
                                viewModel.loadAllFolders()
                                dialog = LibraryDialog.Move(item)
                            }
                            ItemAction.Delete -> dialog = LibraryDialog.Delete(item)
                        }
                    },
                )
            }
        }
    }

    LibraryDialogs(dialog, state, viewModel, client, shared, onClose = {
        dialog = null
        sharedUrl.value = null
    })
}

@Composable
private fun LibraryDialogs(
    dialog: LibraryDialog?,
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    client: AsmrClient,
    shared: String?,
    onClose: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        LibraryDialog.Import -> ImportDialog(
            initialUrl = shared.orEmpty(),
            onImport = {
                viewModel.startImport(it)
                onClose()
            },
            onDismiss = onClose,
        )
        LibraryDialog.Imports -> ImportsSheet(state.imports, onDismiss = onClose)
        LibraryDialog.NewFolder -> NewFolderDialog(state.path, onCreate = {
            viewModel.createFolder(it)
            onClose()
        }, onDismiss = onClose)
        is LibraryDialog.Edit -> ItemEditor(dialog.item, onSave = { request ->
            viewModel.saveItem(dialog.item.id, request)
            onClose()
        }, onDismiss = onClose)
        is LibraryDialog.Move -> MoveDialog(dialog.item, state.allFolders, onMove = { folder ->
            viewModel.saveItem(dialog.item.id, UpdateItemRequest(folder = folder), done = "Verschoben")
            onClose()
        }, onDismiss = onClose)
        is LibraryDialog.Delete -> ConfirmDeleteDialog(dialog.item, onConfirm = {
            viewModel.deleteItem(dialog.item)
            onClose()
        }, onDismiss = onClose)
        is LibraryDialog.Access -> AccessDialog(dialog.folder.path, state.access, onSave = { restricted, groups, users ->
            viewModel.saveAccess(dialog.folder.path, restricted, groups, users)
            onClose()
        }, onDismiss = onClose)
        is LibraryDialog.Creator -> CreatorSheet(
            name = dialog.name,
            client = client,
            serverUrl = state.serverUrl,
            isAdmin = state.isAdmin,
            onImagesChanged = viewModel::imagesChanged,
            onDismiss = {
                onClose()
                viewModel.reload()
            },
        )
    }
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    downloads: Map<Long, OfflineState>,
    onShowDownloads: () -> Unit,
    onPlay: (Int) -> Unit,
    onFolderAction: (FolderDto, FolderAction) -> Unit,
    onItemAction: (ItemDto, ItemAction) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val error = state.error
        when {
            state.isLoading && state.folders.isEmpty() && state.items.isEmpty() ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))

            error != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (state.offline) "Server nicht erreichbar" else error, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::reload) { Text("Erneut versuchen") }
                if (state.offline) {
                    OutlinedButton(onClick = onShowDownloads) { Text("Heruntergeladene Tracks") }
                }
            }

            state.folders.isEmpty() && state.items.isEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (state.path.isEmpty()) "Noch keine Inhalte" else "Dieser Ordner ist leer")
                if (state.path.isEmpty()) {
                    Text(
                        "Über + eine URL importieren oder in der YouTube-App \"Teilen\" → ASMR Player.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.folders, key = { "folder-${it.path}" }) { folder ->
                    FolderCard(folder, state, onOpen = { viewModel.open(folder.path) }, onAction = { onFolderAction(folder, it) })
                }
                if (state.folders.isNotEmpty() && state.items.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Tracks", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    ItemCard(item, state, downloads[item.id], onPlay = { onPlay(index) }, onAction = { onItemAction(item, it) })
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(parts: List<String>, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen("") }) { Text("Bibliothek") }
        parts.forEachIndexed { index, part ->
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOpen(parts.take(index + 1).joinToString("/")) }, enabled = index < parts.lastIndex) {
                Text(part, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(folder: FolderDto, state: LibraryUiState, onOpen: () -> Unit, onAction: (FolderAction) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Cover(
                url = if (folder.hasCover) "${AsmrClient.folderCoverUrl(state.serverUrl, folder.path)}&v=${state.imageVersion}" else null,
                placeholder = AppIcons.Folder,
            )
            if (folder.restricted) {
                Icon(
                    AppIcons.Lock,
                    contentDescription = "Eingeschränkt",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuEntry("Creator-Profil") { menuOpen = false; onAction(FolderAction.Creator) }
                MenuEntry("Ordner herunterladen") { menuOpen = false; onAction(FolderAction.Download) }
                if (state.isAdmin) MenuEntry("Zugriff festlegen") { menuOpen = false; onAction(FolderAction.Access) }
            }
        }
        Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${folder.itemCount} Tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemCard(
    item: ItemDto,
    state: LibraryUiState,
    download: OfflineState?,
    onPlay: () -> Unit,
    onAction: (ItemAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.combinedClickable(onClick = onPlay, onLongClick = { menuOpen = true }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Cover(
                url = if (item.hasCover) "${AsmrClient.coverUrl(state.serverUrl, item.id)}?v=${state.imageVersion}" else null,
                placeholder = AppIcons.Play,
            )
            // Offline verfügbar (Haken) bzw. Fortschritt eines laufenden Downloads.
            if (download != null) {
                Text(
                    if (download.downloaded) "✓ offline" else "${download.percent} %",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (download == null) {
                    MenuEntry("Herunterladen") { menuOpen = false; onAction(ItemAction.Download) }
                } else {
                    MenuEntry("Download entfernen") { menuOpen = false; onAction(ItemAction.RemoveDownload) }
                }
                MenuEntry("Als Ambient-Spur") { menuOpen = false; onAction(ItemAction.Ambient) }
                if (item.creator != null) MenuEntry("Creator-Profil") { menuOpen = false; onAction(ItemAction.Creator) }
                if (state.isAdmin) {
                    MenuEntry("Bearbeiten & bewerten") { menuOpen = false; onAction(ItemAction.Edit) }
                    MenuEntry("Verschieben") { menuOpen = false; onAction(ItemAction.Move) }
                    MenuEntry("Löschen") { menuOpen = false; onAction(ItemAction.Delete) }
                }
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
        // Die drei stärksten Trigger als Kurzinfo.
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

@Composable
private fun Cover(url: String?, placeholder: ImageVector) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
