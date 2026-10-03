package de.axelcypher.asmr.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.axelcypher.asmr.data.api.AbsClient
import de.axelcypher.asmr.data.api.AbsLibrary
import de.axelcypher.asmr.data.api.AsmrItem
import de.axelcypher.asmr.data.settings.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val serverUrl: String = "",
    val libraries: List<AbsLibrary> = emptyList(),
    val selectedLibraryId: String? = null,
    val items: List<AsmrItem> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val selectedLibrary get() = libraries.find { it.id == selectedLibraryId }
    val canLoadMore get() = items.size < total
}

class LibraryViewModel(
    private val absClient: AbsClient,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() = load {
        val session = sessionStore.current() ?: return@load
        val libraries = absClient.libraries()
        val storedId = sessionStore.selectedLibraryId.first()
        val selected = libraries.find { it.id == storedId } ?: libraries.firstOrNull()
        _state.update {
            it.copy(
                serverUrl = session.serverUrl,
                libraries = libraries,
                selectedLibraryId = selected?.id,
                items = emptyList(),
                total = 0,
            )
        }
        if (selected != null) loadPage(selected.id, page = 0)
    }

    fun selectLibrary(libraryId: String) {
        if (libraryId == _state.value.selectedLibraryId) return
        _state.update { it.copy(selectedLibraryId = libraryId, items = emptyList(), total = 0) }
        load {
            sessionStore.selectLibrary(libraryId)
            loadPage(libraryId, page = 0)
        }
    }

    fun loadMore() {
        val current = _state.value
        val libraryId = current.selectedLibraryId ?: return
        if (current.isLoading || !current.canLoadMore) return
        load { loadPage(libraryId, page = current.items.size / AbsClient.PAGE_SIZE) }
    }

    fun logout() {
        viewModelScope.launch { absClient.logout() }
    }

    private suspend fun loadPage(libraryId: String, page: Int) {
        val result = absClient.libraryItems(libraryId, page)
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
}
