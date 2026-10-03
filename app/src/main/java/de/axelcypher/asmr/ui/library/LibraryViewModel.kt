package de.axelcypher.asmr.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.axelcypher.asmr.api.AccessOverviewDto
import de.axelcypher.asmr.api.CategoryDto
import de.axelcypher.asmr.api.CategoryRequest
import de.axelcypher.asmr.api.HomeDto
import de.axelcypher.asmr.api.PlaylistDto
import de.axelcypher.asmr.api.TagDto
import de.axelcypher.asmr.api.FolderAccessDto
import de.axelcypher.asmr.api.FolderDto
import de.axelcypher.asmr.api.ImportJobDto
import de.axelcypher.asmr.api.ImportStatus
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.PlaybackSettings
import de.axelcypher.asmr.data.settings.SessionStore
import de.axelcypher.asmr.data.settings.UpdateChannel
import de.axelcypher.asmr.playback.OfflineStore
import de.axelcypher.asmr.update.AppUpdate
import de.axelcypher.asmr.update.AppUpdater
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val serverUrl: String = "",
    val isAdmin: Boolean = false,
    /** Aktueller Ordner, "" ist die oberste Ebene. */
    val path: String = "",
    val folders: List<FolderDto> = emptyList(),
    val items: List<ItemDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val imports: List<ImportJobDto> = emptyList(),
    val update: AppUpdate? = null,
    val isDownloadingUpdate: Boolean = false,
    /** Für den Zugriffs-Dialog, nur für Admins geladen. */
    val access: AccessOverviewDto? = null,
    /** Für den Verschieben-Dialog. */
    val allFolders: List<String> = emptyList(),
    /** Zählt hoch, wenn sich Bilder geändert haben; hängt als Parameter an Cover-URLs. */
    val imageVersion: Int = 0,
    /** Server nicht erreichbar: die Oberfläche bietet die heruntergeladenen Tracks an. */
    val offline: Boolean = false,
    /** Startseite (Übersicht). */
    val home: HomeDto? = null,
    /** Geöffnete Detailansicht (Creator, Kategorie, Playlist, Favoriten, Ambiente). */
    val detail: Detail? = null,
    val detailItems: List<ItemDto> = emptyList(),
    /** Bei einer Playlist-Detailansicht die Playlist selbst. */
    val detailPlaylist: PlaylistDto? = null,
    val playlists: List<PlaylistDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    /** Für den Zuordnungs-Dialog: Kategorie-IDs eines Tracks bzw. Ordners. */
    val categoryMembership: Set<Long> = emptySet(),
    val search: SearchFilters = SearchFilters(),
    val searchResults: List<ItemDto> = emptyList(),
    val isSearching: Boolean = false,
    /** Trigger mit Anzahl für die Filterauswahl. */
    val availableTags: List<TagDto> = emptyList(),
    /** Bewertungsmatrix vom Server (Gruppe -> Regler); bis zum Laden der eingebaute Katalog. */
    val triggerCatalog: List<Pair<String, List<String>>> = de.axelcypher.asmr.api.TRIGGER_CATALOG,
) {
    val runningImports get() = imports.count { it.status == ImportStatus.QUEUED || it.status == ImportStatus.RUNNING }
    val breadcrumbs: List<String> get() = if (path.isEmpty()) emptyList() else path.split('/')
}

class LibraryViewModel(
    private val client: AsmrClient,
    private val sessionStore: SessionStore,
    private val settings: PlaybackSettings,
    private val updater: AppUpdater,
    private val offlineStore: OfflineStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state

    /** Kurze Meldungen für die Snackbar. */
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()


    val updateChannel = settings.updateChannel

    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var importsLoaded = false
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val session = sessionStore.current() ?: return@launch
            _state.update { it.copy(serverUrl = session.serverUrl) }
        }
        open("")
        loadHome()
        refreshImports()
        checkForUpdate()
    }

    // --- Navigation -----------------------------------------------------------------------------

    fun open(path: String) {
        loadJob?.cancel()
        _state.update { it.copy(path = path, isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val listing = client.folder(path)
                _state.update { it.copy(folders = listing.folders, items = listing.items, isLoading = false, offline = false) }
                // Rolle bei jedem Laden auffrischen: ein einzelner Fehlschlag (Server-Neustart, Funkloch)
                // darf die Admin-Funktionen nicht bis zum App-Neustart abschalten.
                runCatching { client.me() }.getOrNull()?.let { me -> _state.update { it.copy(isAdmin = me.isAdmin) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Laden fehlgeschlagen", offline = e is java.io.IOException)
                }
            }
        }
    }

    fun reload() = open(_state.value.path)

    /** Eine Ebene hoch; false, wenn schon oben. */
    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        open(path.substringBeforeLast('/', missingDelimiterValue = ""))
        return true
    }

    // --- Bearbeiten (Admin) ---------------------------------------------------------------------

    fun saveItem(id: Long, request: UpdateItemRequest, done: String = "Gespeichert") = action(done) {
        client.updateItem(id, request)
        refreshAll()
    }

    fun deleteItem(item: ItemDto) = action("\"${item.title}\" gelöscht") {
        client.deleteItem(item.id)
        refreshAll()
    }

    fun createFolder(name: String) = action("Ordner angelegt") {
        val path = _state.value.path
        client.createFolder(if (path.isEmpty()) name.trim() else "$path/${name.trim()}")
        reload()
    }

    fun loadAllFolders() = action(null) {
        _state.update { it.copy(allFolders = client.allFolders()) }
    }

    fun loadAccess() = action(null) {
        _state.update { it.copy(access = client.accessOverview()) }
    }

    /** Ohne Gruppen und Benutzer wird die Einschränkung aufgehoben. */
    fun saveAccess(path: String, restricted: Boolean, groups: List<String>, userIds: List<Long>) =
        action(if (restricted) "Zugriff eingeschränkt" else "Für alle sichtbar") {
            if (restricted) client.setAccess(FolderAccessDto(path, groups, userIds)) else client.removeAccess(path)
            _state.update { it.copy(access = client.accessOverview()) }
            reload()
        }

    fun imagesChanged() = _state.update { it.copy(imageVersion = it.imageVersion + 1) }

    // --- Übersicht ------------------------------------------------------------------------------

    fun loadHome() = action(null) {
        val home = client.home()
        _state.update { it.copy(home = home, playlists = home.playlists, categories = home.categories) }
        runCatching { client.catalog() }.getOrNull()?.let { catalog ->
            _state.update { it.copy(triggerCatalog = catalog.triggers.map { group -> group.name to group.triggers }) }
        }
    }

    fun openDetail(detail: Detail) {
        _state.update { it.copy(detail = detail, detailItems = emptyList(), detailPlaylist = null) }
        loadDetail()
    }

    fun closeDetail() = _state.update { it.copy(detail = null, detailItems = emptyList(), detailPlaylist = null) }

    private fun loadDetail() = action(null) {
        when (val detail = _state.value.detail) {
            null -> Unit
            is Detail.Creator -> setDetailItems(client.itemsWhere("creator" to detail.name))
            is Detail.Category -> setDetailItems(client.itemsWhere("category" to detail.category.id.toString()))
            Detail.Favorites -> setDetailItems(client.itemsWhere("favorites" to "true"))
            Detail.Ambient -> setDetailItems(client.itemsWhere("ambient" to "true"))
            Detail.Playlists -> _state.update { it.copy(playlists = client.playlists()) }
            is Detail.Playlist -> {
                val playlist = client.playlist(detail.id)
                _state.update { it.copy(detailItems = playlist.items, detailPlaylist = playlist.playlist) }
            }
        }
    }

    private fun setDetailItems(items: List<ItemDto>) = _state.update { it.copy(detailItems = items) }

    /** Nach einer Änderung alles Sichtbare neu laden (Ordner, Startseite, Detailansicht). */
    private fun refreshAll() {
        reload()
        loadHome()
        loadDetail()
    }

    fun toggleFavorite(item: ItemDto) = action(if (item.isFavorite) "Aus Favoriten entfernt" else "Zu Favoriten hinzugefügt") {
        client.setFavorite(item.id, !item.isFavorite)
        refreshAll()
    }

    fun setItemAmbient(item: ItemDto, ambient: Boolean) = action(if (ambient) "Als Ambiente markiert" else "Ambiente entfernt") {
        client.setItemAmbient(item.id, ambient)
        refreshAll()
    }

    fun setFolderAmbient(path: String, ambient: Boolean) = action(if (ambient) "Ordner als Ambiente markiert" else "Ambiente entfernt") {
        client.setFolderAmbient(path, ambient)
        refreshAll()
    }

    // --- Suche ---------------------------------------------------------------------------------

    /** Ändert Suchtext oder Filter; gesucht wird kurz nach der letzten Änderung. */
    fun updateSearch(change: (SearchFilters) -> SearchFilters) {
        _state.update { it.copy(search = change(it.search)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val search = _state.value.search
            if (!search.isActive) {
                _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
                return@launch
            }
            _state.update { it.copy(isSearching = true) }
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val results = client.search(
                    search.query, search.tags, search.creator, search.favoritesOnly,
                    search.length.minSeconds, search.length.maxSeconds, search.sort,
                )
                _state.update { it.copy(searchResults = results, isSearching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSearching = false) }
                messageChannel.send("Suche fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun clearSearch() = updateSearch { SearchFilters() }

    fun loadTags() = action(null) { _state.update { it.copy(availableTags = client.tags()) } }

    // --- Kategorien (Admin) ---------------------------------------------------------------------

    fun saveCategory(id: Long?, request: CategoryRequest) = action(if (id == null) "Kategorie angelegt" else "Gespeichert") {
        client.saveCategory(id, request)
        loadHome()
    }

    fun deleteCategory(id: Long) = action("Kategorie gelöscht") {
        client.deleteCategory(id)
        closeDetail()
        loadHome()
    }

    /** Lädt, in welchen Kategorien ein Track bzw. Ordner steckt (für den Zuordnungs-Dialog). */
    fun loadMembership(itemId: Long?, folder: String?) = action(null) {
        val categories = client.categories()
        val member = categories.filter { category ->
            val detail = client.categoryDetail(category.id)
            (itemId != null && itemId in detail.itemIds) || (folder != null && folder in detail.folders)
        }.map { it.id }.toSet()
        _state.update { it.copy(categories = categories, categoryMembership = member) }
    }

    fun saveMembership(itemId: Long?, folder: String?, selected: Set<Long>) = action("Kategorien gespeichert") {
        val before = _state.value.categoryMembership
        (selected - before).forEach { id ->
            if (itemId != null) client.setCategoryItem(id, itemId, true) else client.setCategoryFolder(id, folder!!, true)
        }
        (before - selected).forEach { id ->
            if (itemId != null) client.setCategoryItem(id, itemId, false) else client.setCategoryFolder(id, folder!!, false)
        }
        refreshAll()
    }

    // --- Playlists ------------------------------------------------------------------------------

    fun loadPlaylists() = action(null) { _state.update { it.copy(playlists = client.playlists()) } }

    fun addToPlaylist(item: ItemDto, playlistId: Long?, newName: String?) = action("Zur Playlist hinzugefügt") {
        val id = playlistId ?: client.createPlaylist(newName!!.trim()).id
        client.setPlaylistItem(id, item.id, true)
        refreshAll()
    }

    fun removeFromPlaylist(playlistId: Long, item: ItemDto) = action("Aus Playlist entfernt") {
        client.setPlaylistItem(playlistId, item.id, false)
        refreshAll()
    }

    fun createPlaylist(name: String) = action("Playlist angelegt") {
        client.createPlaylist(name.trim())
        refreshAll()
    }

    fun updatePlaylist(id: Long, name: String? = null, shared: Boolean? = null) = action("Gespeichert") {
        client.updatePlaylist(id, name, shared)
        refreshAll()
    }

    fun deletePlaylist(id: Long) = action("Playlist gelöscht") {
        client.deletePlaylist(id)
        closeDetail()
        loadHome()
    }

    // --- Offline --------------------------------------------------------------------------------

    fun download(item: ItemDto) = action("Download gestartet") { offlineStore.download(item, _state.value.serverUrl) }

    fun removeDownload(item: ItemDto) = action("Download entfernt") { offlineStore.remove(item.id) }

    /** Lädt alle Tracks eines Ordners samt Unterordnern herunter. */
    fun downloadFolder(path: String) = action(null) {
        var count = 0
        val pending = ArrayDeque(listOf(path))
        while (pending.isNotEmpty()) {
            val listing = client.folder(pending.removeFirst())
            listing.items.forEach { offlineStore.download(it, _state.value.serverUrl) }
            count += listing.items.size
            pending += listing.folders.map { it.path }
        }
        messageChannel.send("$count Tracks werden heruntergeladen")
    }

    // --- Import ---------------------------------------------------------------------------------

    fun startImport(url: String) = action("Import gestartet") {
        client.startImport(url)
        refreshImports()
    }

    /** Lädt die Importliste und fragt nach, solange noch Jobs laufen. */
    fun refreshImports() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                val before = _state.value.imports.associate { it.id to it.status }
                val jobs = runCatching { client.imports() }.getOrNull() ?: break
                _state.update { it.copy(imports = jobs) }
                // Beim allerersten Laden ist nichts "neu fertig"; danach zählen auch Jobs, die
                // zwischen zwei Abfragen gestartet und schon abgeschlossen wurden.
                val finished = if (!importsLoaded) emptyList() else jobs.filter { before[it.id] != it.status }
                importsLoaded = true
                if (finished.any { it.status == ImportStatus.DONE }) reload()
                finished.filter { it.status == ImportStatus.FAILED }.forEach {
                    messageChannel.send("Import fehlgeschlagen: ${it.error ?: it.url}")
                }
                if (_state.value.runningImports == 0) break
                delay(IMPORT_POLL_MS)
            }
        }
    }

    // --- Updates --------------------------------------------------------------------------------

    fun checkForUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            val update = runCatching { updater.check(settings.updateChannel.first()) }.getOrNull()
            _state.update { it.copy(update = update) }
            if (manual && update == null) messageChannel.send("Keine neuere Version")
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        viewModelScope.launch {
            settings.setUpdateChannel(channel)
            checkForUpdate(manual = true)
        }
    }

    fun installUpdate() {
        val update = _state.value.update ?: return
        _state.update { it.copy(isDownloadingUpdate = true) }
        viewModelScope.launch {
            try {
                updater.install(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.send("Download fehlgeschlagen: ${e.message}")
            } finally {
                _state.update { it.copy(isDownloadingUpdate = false) }
            }
        }
    }

    // --- Intern ---------------------------------------------------------------------------------

    private fun action(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { messageChannel.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                messageChannel.send(e.serverMessage())
            } catch (e: Exception) {
                messageChannel.send(e.message ?: "Fehlgeschlagen")
            }
        }
    }

    private companion object {
        const val IMPORT_POLL_MS = 3000L
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/** Der Server liefert Fehler als {"error": "..."}. */
suspend fun ClientRequestException.serverMessage(): String =
    response.bodyAsText().substringAfter("\"error\":\"", "").substringBefore('"')
        .ifEmpty { "Fehler ${response.status.value}" }

/** Detailansichten der Übersicht. */
sealed interface Detail {
    data class Creator(val name: String) : Detail
    data class Category(val category: CategoryDto) : Detail
    data class Playlist(val id: Long) : Detail
    data object Favorites : Detail
    data object Ambient : Detail
    data object Playlists : Detail
}

enum class LengthFilter(val label: String, val minSeconds: Int?, val maxSeconds: Int?) {
    Any("Egal", null, null),
    Short("Bis 15 min", null, 15 * 60),
    Medium("15–45 min", 15 * 60, 45 * 60),
    Long("Über 45 min", 45 * 60, null),
}

data class SearchFilters(
    val query: String = "",
    /** Alle müssen vorkommen (Stärke > 0). */
    val tags: Set<String> = emptySet(),
    val creator: String? = null,
    val favoritesOnly: Boolean = false,
    val length: LengthFilter = LengthFilter.Any,
    val sort: de.axelcypher.asmr.api.ItemSort = de.axelcypher.asmr.api.ItemSort.TITLE,
) {
    /** Anzahl gesetzter Filter (ohne Suchtext und Sortierung), für das Badge am Filter-Knopf. */
    val filterCount get() = tags.size + listOfNotNull(creator).size + (if (favoritesOnly) 1 else 0) + (if (length != LengthFilter.Any) 1 else 0)

    val isActive get() = query.isNotBlank() || filterCount > 0
}
