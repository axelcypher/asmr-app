package de.axelcypher.asmr.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val serverUrl: String = "",
    val items: List<ItemDto> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore get() = items.size < total
}

class LibraryViewModel(
    private val client: AsmrClient,
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
        _state.update { it.copy(serverUrl = session.serverUrl, items = emptyList(), total = 0) }
        loadPage(page = 0)
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || !current.canLoadMore) return
        load { loadPage(page = current.items.size / AsmrClient.PAGE_SIZE) }
    }

    fun logout() {
        viewModelScope.launch { client.logout() }
    }

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
}
