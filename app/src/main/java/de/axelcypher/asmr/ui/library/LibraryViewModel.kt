package de.axelcypher.asmr.ui.library

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.axelcypher.asmr.api.ImportJobDto
import de.axelcypher.asmr.api.ImportStatus
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.PlaybackSettings
import de.axelcypher.asmr.data.settings.SessionStore
import de.axelcypher.asmr.data.settings.UpdateChannel
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
    val items: List<ItemDto> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val imports: List<ImportJobDto> = emptyList(),
    val update: AppUpdate? = null,
    val isDownloadingUpdate: Boolean = false,
) {
    val canLoadMore get() = items.size < total
    val runningImports get() = imports.count { it.status == ImportStatus.QUEUED || it.status == ImportStatus.RUNNING }
}

class LibraryViewModel(
    private val client: AsmrClient,
    private val sessionStore: SessionStore,
    private val settings: PlaybackSettings,
    private val updater: AppUpdater,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state

    /** Kurze Meldungen für die Snackbar. */
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    /** Intents, die die Oberfläche starten soll (Installer fürs Update). */
    private val intentChannel = Channel<Intent>(Channel.BUFFERED)
    val intents = intentChannel.receiveAsFlow()

    val updateChannel = settings.updateChannel

    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var importsLoaded = false

    init {
        reload()
        refreshImports()
        checkForUpdate()
    }

    fun reload() = load {
        val session = sessionStore.current() ?: return@load
        _state.update { it.copy(serverUrl = session.serverUrl, items = emptyList(), total = 0) }
        loadPage(page = 0)
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || !current.canLoadMore) return
        load { loadPage(page = current.items.size / AsmrClient.PAGE_SIZE) }
    }

    // --- Import ---------------------------------------------------------------------------------

    fun startImport(url: String) {
        viewModelScope.launch {
            try {
                client.startImport(url)
                messageChannel.send("Import gestartet")
                refreshImports()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                messageChannel.send(e.response.bodyAsText().substringAfter("\"error\":\"").substringBefore('"'))
            } catch (e: Exception) {
                messageChannel.send("Import fehlgeschlagen: ${e.message}")
            }
        }
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
                intentChannel.send(updater.download(update))
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

    private suspend fun loadPage(page: Int) {
        val result = client.items(page)
        _state.update { it.copy(items = it.items + result.items, total = result.total) }
    }

    private fun load(block: suspend () -> Unit) {
        loadJob?.cancel()
        _state.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Laden fehlgeschlagen") }
            }
        }
    }

    private companion object {
        const val IMPORT_POLL_MS = 3000L
    }
}
