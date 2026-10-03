package de.axelcypher.asmr.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.axelcypher.asmr.data.api.AbsClient
import de.axelcypher.asmr.data.settings.SessionStore
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit get() = serverUrl.isNotBlank() && username.isNotBlank() && !isLoading
}

class LoginViewModel(
    private val absClient: AbsClient,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    init {
        viewModelScope.launch {
            val lastUrl = sessionStore.lastServerUrl.first() ?: return@launch
            _state.update { if (it.serverUrl.isEmpty()) it.copy(serverUrl = lastUrl) else it }
        }
    }

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun login() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val session = absClient.login(current.serverUrl, current.username, current.password)
                sessionStore.save(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.toLoginMessage()) }
            }
        }
    }

    private fun Exception.toLoginMessage() = when {
        this is ClientRequestException && response.status == HttpStatusCode.Unauthorized ->
            "Benutzername oder Passwort falsch"
        this is ClientRequestException -> "Server antwortet mit ${response.status.value}"
        this is IOException -> "Server nicht erreichbar"
        else -> message ?: "Anmeldung fehlgeschlagen"
    }
}
