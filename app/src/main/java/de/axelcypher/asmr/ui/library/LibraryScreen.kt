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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.axelcypher.asmr.api.CategoryDto
import de.axelcypher.asmr.api.FolderDto
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.PlaylistDto
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.UpdateChannel
import de.axelcypher.asmr.playback.OfflineStore
import de.axelcypher.asmr.playback.PlaybackEngine
import de.axelcypher.asmr.ui.AppIcons
import de.axelcypher.asmr.ui.creator.CreatorSheet
import de.axelcypher.asmr.ui.imports.ImportDialog
import de.axelcypher.asmr.ui.imports.ImportsSheet
import de.axelcypher.asmr.ui.player.NowPlayingBar
import de.axelcypher.asmr.ui.player.PlayerScreen
import de.axelcypher.asmr.ui.player.Tab
import de.axelcypher.asmr.ui.profile.ProfileScreen
import de.axelcypher.asmr.ui.profile.ProfileViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/** Was gerade als Dialog offen ist; nur eins gleichzeitig. */
private sealed interface LibraryDialog {
    data object Import : LibraryDialog
    data object Imports : LibraryDialog
    data object NewFolder : LibraryDialog
    data object NewPlaylist : LibraryDialog
    data class Edit(val item: ItemDto) : LibraryDialog
    data class Move(val item: ItemDto) : LibraryDialog
    data class Delete(val item: ItemDto) : LibraryDialog
    data class Access(val folder: FolderDto) : LibraryDialog
    data class Creator(val name: String) : LibraryDialog
    data class AddToPlaylist(val item: ItemDto) : LibraryDialog
    data class CategoryEdit(val category: CategoryDto?) : LibraryDialog
    data class Membership(val title: String, val itemId: Long?, val folder: String?) : LibraryDialog
    data class RenamePlaylist(val playlist: PlaylistDto) : LibraryDialog
    data class DeletePlaylist(val playlist: PlaylistDto) : LibraryDialog
}

private enum class FolderAction { Access, Creator, Download, Categories }

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
    val downloads by offline.states.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    var folderView by rememberSaveable { mutableStateOf(false) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        (context.applicationContext as de.axelcypher.asmr.AsmrApp).container.updateMessages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(shared) { if (shared != null) dialog = LibraryDialog.Import }

    val cards = CardContext(state.serverUrl, state.imageVersion, state.isAdmin, downloads)

    // Benachrichtigung für Lockscreen-Steuerung; ohne Erlaubnis spielt es trotzdem.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun play(items: List<ItemDto>, index: Int) {
        askNotifications()
        engine.play(items, index)
        showPlayer = true
    }
    fun playAmbient(item: ItemDto) {
        askNotifications()
        engine.playAmbient(item)
    }
    fun toggleFavorite(item: ItemDto) {
        viewModel.toggleFavorite(item)
        engine.updateQueueItem(item.copy(isFavorite = !item.isFavorite))
    }
    fun onItemAction(item: ItemDto, action: ItemAction) {
        when (action) {
            ItemAction.Layer -> engine.setAmbient(item)
            ItemAction.Favorite -> toggleFavorite(item)
            ItemAction.AddToPlaylist -> {
                viewModel.loadPlaylists()
                dialog = LibraryDialog.AddToPlaylist(item)
            }
            ItemAction.RemoveFromPlaylist -> state.detailPlaylist?.let { viewModel.removeFromPlaylist(it.id, item) }
            ItemAction.Creator -> dialog = item.creator?.let(LibraryDialog::Creator)
            ItemAction.Download -> viewModel.download(item)
            ItemAction.RemoveDownload -> viewModel.removeDownload(item)
            ItemAction.Edit -> dialog = LibraryDialog.Edit(item)
            ItemAction.Categories -> {
                viewModel.loadMembership(item.id, null)
                dialog = LibraryDialog.Membership("Kategorien: ${item.title}", item.id, null)
            }
            ItemAction.Move -> {
                viewModel.loadAllFolders()
                dialog = LibraryDialog.Move(item)
            }
            ItemAction.Delete -> dialog = LibraryDialog.Delete(item)
        }
    }

    if (showPlayer) {
        PlayerScreen(
            engine,
            state.serverUrl,
            onClose = { showPlayer = false },
            onCreator = { dialog = LibraryDialog.Creator(it) },
            onFavorite = ::toggleFavorite,
        )
        LibraryDialogs(dialog, state, viewModel, client, shared, onClose = { dialog = null; sharedUrl.value = null })
        return
    }

    val detail = state.detail
    BackHandler(enabled = tab == Tab.Library && !showDownloads && (detail != null || folderView || state.search.isActive)) {
        when {
            detail != null -> viewModel.closeDetail()
            folderView && state.path.isNotEmpty() -> viewModel.up()
            folderView -> folderView = false
            else -> viewModel.clearSearch()
        }
    }

    Scaffold(
        topBar = {
            if (tab == Tab.Library && !showDownloads) {
                TopAppBar(
                    title = {
                        val title = detail?.title(state)
                            ?: if (folderView) state.breadcrumbs.lastOrNull() ?: "Bibliothek" else "Bibliothek"
                        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (detail != null || (folderView && state.path.isNotEmpty())) {
                            IconButton(onClick = { if (detail != null) viewModel.closeDetail() else viewModel.up() }) {
                                Icon(AppIcons.ArrowBack, "Zurück")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { dialog = LibraryDialog.Import }) { Icon(AppIcons.Add, "Importieren") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(AppIcons.MoreVert, "Menü") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (state.isAdmin && folderView) {
                                    MenuEntry("Neuer Ordner") { menuOpen = false; dialog = LibraryDialog.NewFolder }
                                }
                                MenuEntry("Heruntergeladen") { menuOpen = false; showDownloads = true }
                                MenuEntry("Importe" + if (state.runningImports > 0) " (${state.runningImports} laufen)" else "") {
                                    menuOpen = false
                                    dialog = LibraryDialog.Imports
                                    viewModel.refreshImports()
                                }
                                MenuEntry("Aktualisieren") { menuOpen = false; viewModel.reload(); viewModel.loadHome() }
                                MenuEntry("Update-Kanal: " + if (channel == UpdateChannel.STABLE) "Stabil" else "Nightly") {
                                    menuOpen = false
                                    viewModel.setUpdateChannel(
                                        if (channel == UpdateChannel.STABLE) UpdateChannel.NIGHTLY else UpdateChannel.STABLE,
                                    )
                                }
                                MenuEntry("Nach Updates suchen") { menuOpen = false; viewModel.checkForUpdate(manual = true) }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            NowPlayingBar(
                engine = engine,
                coverUrl = { id -> offline.localCover(id)?.toString() ?: "${AsmrClient.coverUrl(state.serverUrl, id)}?v=${state.imageVersion}" },
                tab = tab,
                onTab = {
                    tab = it
                    showDownloads = false
                },
                onOpenPlayer = { showPlayer = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                tab == Tab.Profile -> ProfileScreen(
                    viewModel { ProfileViewModel(client) },
                    onClose = { tab = Tab.Library },
                )

                showDownloads -> DownloadsScreen(
                    offline = offline,
                    onPlay = ::play,
                    onAmbient = engine::setAmbient,
                    onClose = { showDownloads = false },
                    bottomBar = {},
                )

                detail != null -> DetailContent(
                    detail, state, cards,
                    DetailCallbacks(
                        onPlay = ::play,
                        onAmbientTap = ::playAmbient,
                        onItemAction = ::onItemAction,
                        onCreatorProfile = { dialog = LibraryDialog.Creator(it) },
                        onCategoryEdit = { dialog = LibraryDialog.CategoryEdit(it) },
                        onPlaylist = { viewModel.openDetail(Detail.Playlist(it.id)) },
                        onNewPlaylist = { dialog = LibraryDialog.NewPlaylist },
                        onRenamePlaylist = { dialog = LibraryDialog.RenamePlaylist(it) },
                        onToggleShare = { viewModel.updatePlaylist(it.id, shared = !it.shared) },
                        onDeletePlaylist = { dialog = LibraryDialog.DeletePlaylist(it) },
                    ),
                )

                else -> Column(Modifier.fillMaxSize()) {
                    UpdateBanner(state, viewModel)
                    if (folderView) {
                        if (state.breadcrumbs.isNotEmpty()) Breadcrumbs(state.breadcrumbs, onOpen = viewModel::open)
                        FolderContent(
                            state = state,
                            cards = cards,
                            viewModel = viewModel,
                            onShowDownloads = { showDownloads = true },
                            onOverview = { folderView = false },
                            onPlay = ::play,
                            onItemAction = ::onItemAction,
                            onFolderAction = { folder, action ->
                                when (action) {
                                    FolderAction.Access -> {
                                        viewModel.loadAccess()
                                        dialog = LibraryDialog.Access(folder)
                                    }
                                    FolderAction.Creator -> dialog = LibraryDialog.Creator(folder.name)
                                    FolderAction.Download -> viewModel.downloadFolder(folder.path)
                                    FolderAction.Categories -> {
                                        viewModel.loadMembership(null, folder.path)
                                        dialog = LibraryDialog.Membership("Kategorien: ${folder.name}", null, folder.path)
                                    }
                                }
                            },
                        )
                    } else if (state.home == null && state.offline) {
                        OfflineHint(viewModel, onShowDownloads = { showDownloads = true })
                    } else {
                        SearchBar(
                            state.search,
                            onQuery = { query -> viewModel.updateSearch { it.copy(query = query) } },
                            onClear = viewModel::clearSearch,
                            onFilters = {
                                viewModel.loadTags()
                                showFilters = true
                            },
                        )
                        if (state.search.isActive) {
                            SearchResults(state.searchResults, state.isSearching, cards, onPlay = ::play, onItemAction = ::onItemAction)
                        } else {
                            HomeContent(
                            state.home, cards,
                            HomeCallbacks(
                                onCreator = { viewModel.openDetail(Detail.Creator(it)) },
                                onPlay = ::play,
                                onAmbientTap = ::playAmbient,
                                onItemAction = ::onItemAction,
                                onSeeAll = viewModel::openDetail,
                                onPlaylist = { viewModel.openDetail(Detail.Playlist(it.id)) },
                                onCategory = { viewModel.openDetail(Detail.Category(it)) },
                                onCategoryEdit = { dialog = LibraryDialog.CategoryEdit(it) },
                            ),
                            footer = { SubtleLink("Ordneransicht") { folderView = true } },
                        )
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            search = state.search,
            tags = state.availableTags,
            creators = state.home?.creators?.map { it.name }.orEmpty(),
            onChange = viewModel::updateSearch,
            onDismiss = { showFilters = false },
        )
    }

    LibraryDialogs(dialog, state, viewModel, client, shared, onClose = {
        dialog = null
        sharedUrl.value = null
    })
}

/** Unauffälliger Wechsel zwischen Übersicht und Ordneransicht, jeweils am Ende der Liste. */
@Composable
fun SubtleLink(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateBanner(state: LibraryUiState, viewModel: LibraryViewModel) {
    val update = state.update ?: return
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

@Composable
private fun OfflineHint(viewModel: LibraryViewModel, onShowDownloads: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Server nicht erreichbar", color = MaterialTheme.colorScheme.error)
        Button(onClick = { viewModel.reload(); viewModel.loadHome() }) { Text("Erneut versuchen") }
        OutlinedButton(onClick = onShowDownloads) { Text("Heruntergeladene Tracks") }
    }
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
        LibraryDialog.NewPlaylist -> TextInputDialog("Neue Playlist", "Name", "", "Anlegen", onConfirm = {
            viewModel.createPlaylist(it)
            onClose()
        }, onDismiss = onClose)
        is LibraryDialog.Edit -> ItemEditor(dialog.item, state.triggerCatalog, onSave = { request ->
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
                viewModel.loadHome()
            },
        )
        is LibraryDialog.AddToPlaylist -> AddToPlaylistDialog(
            playlists = state.playlists,
            onPick = {
                viewModel.addToPlaylist(dialog.item, it, null)
                onClose()
            },
            onCreate = {
                viewModel.addToPlaylist(dialog.item, null, it)
                onClose()
            },
            onDismiss = onClose,
        )
        is LibraryDialog.CategoryEdit -> CategoryDialog(
            existing = dialog.category,
            onSave = {
                viewModel.saveCategory(dialog.category?.id, it)
                onClose()
            },
            onDelete = {
                dialog.category?.let { viewModel.deleteCategory(it.id) }
                onClose()
            },
            onDismiss = onClose,
        )
        is LibraryDialog.Membership -> MembershipDialog(
            title = dialog.title,
            categories = state.categories,
            initial = state.categoryMembership,
            onSave = {
                viewModel.saveMembership(dialog.itemId, dialog.folder, it)
                onClose()
            },
            onDismiss = onClose,
        )
        is LibraryDialog.RenamePlaylist -> TextInputDialog("Playlist umbenennen", "Name", dialog.playlist.name, "Speichern", onConfirm = {
            viewModel.updatePlaylist(dialog.playlist.id, name = it)
            onClose()
        }, onDismiss = onClose)
        is LibraryDialog.DeletePlaylist -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Playlist löschen?") },
            text = { Text("\"${dialog.playlist.name}\" wird gelöscht. Die Tracks selbst bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(dialog.playlist.id)
                    onClose()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun FolderContent(
    state: LibraryUiState,
    cards: CardContext,
    viewModel: LibraryViewModel,
    onShowDownloads: () -> Unit,
    onOverview: () -> Unit,
    onPlay: (List<ItemDto>, Int) -> Unit,
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
                if (state.offline) OutlinedButton(onClick = onShowDownloads) { Text("Heruntergeladene Tracks") }
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
                    TrackCard(item, cards, onClick = { onPlay(state.items, index) }, onAction = { onItemAction(item, it) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SubtleLink("Zur Übersicht", onOverview) }
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
                modifier = Modifier.fillMaxWidth(),
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
                MenuEntry("ASMRtist-Profil") { menuOpen = false; onAction(FolderAction.Creator) }
                MenuEntry("Ordner herunterladen") { menuOpen = false; onAction(FolderAction.Download) }
                if (state.isAdmin) {
                    MenuEntry("Kategorien") { menuOpen = false; onAction(FolderAction.Categories) }
                    MenuEntry("Zugriff festlegen") { menuOpen = false; onAction(FolderAction.Access) }
                }
            }
        }
        Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${folder.itemCount} Tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
