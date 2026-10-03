package de.axelcypher.asmr.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.axelcypher.asmr.api.UserDto
import de.axelcypher.asmr.data.api.AsmrClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val me: UserDto? = null,
    /** Nur für Admins gefüllt. */
    val users: List<UserDto> = emptyList(),
    val isBusy: Boolean = false,
)

class ProfileViewModel(private val client: AsmrClient) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() = run(success = null) {
        val me = client.me()
        _state.update { it.copy(me = me, users = if (me.isAdmin) client.users() else emptyList()) }
    }

    fun changePassword(current: String?, new: String, onDone: () -> Unit) = run(success = "Passwort geändert") {
        client.changePassword(current, new)
        _state.update { it.copy(me = client.me()) }
        onDone()
    }

    fun createUser(username: String, password: String, isAdmin: Boolean, onDone: () -> Unit) =
        run(success = "Konto $username angelegt") {
            client.createUser(username, password, isAdmin)
            _state.update { it.copy(users = client.users()) }
            onDone()
        }

    fun deleteUser(user: UserDto) = run(success = "Konto ${user.username} gelöscht") {
        client.deleteUser(user.id)
        _state.update { it.copy(users = client.users()) }
    }

    fun scanLibrary() = run(success = "Scan gestartet, die Bibliothek füllt sich gleich") {
        client.scanLibrary()
    }

    fun logout() {
        viewModelScope.launch { client.logout() }
    }

    private fun run(success: String?, block: suspend () -> Unit) {
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                block()
                success?.let { messageChannel.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // Der Server liefert {"error": "..."} mit einer lesbaren Meldung.
                val text = e.response.bodyAsText()
                messageChannel.send(text.substringAfter("\"error\":\"", "").substringBefore('"').ifEmpty { "Fehler ${e.response.status.value}" })
            } catch (e: Exception) {
                messageChannel.send(e.message ?: "Fehlgeschlagen")
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }
}
