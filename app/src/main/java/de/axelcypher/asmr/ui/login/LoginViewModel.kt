package de.axelcypher.asmr.ui.login

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.axelcypher.asmr.api.AuthConfigDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.api.Pkce
import de.axelcypher.asmr.data.api.normalizeServerUrl
import de.axelcypher.asmr.data.settings.PendingSso
import de.axelcypher.asmr.data.settings.SessionStore
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class LoginUiState(
    val serverUrl: String = "",
    /** Gesetzt, sobald der Server erreicht wurde; dann folgt der zweite Schritt. */
    val authConfig: AuthConfigDto? = null,
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canConnect get() = serverUrl.isNotBlank() && !isLoading
    val canLogin get() = username.isNotBlank() && password.isNotEmpty() && !isLoading
}

class LoginViewModel(
    private val client: AsmrClient,
    private val sessionStore: SessionStore,
    ssoRedirects: Flow<Uri>,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    /** URLs, die die Oberfläche im Browser (Custom Tab) öffnen soll. */
    private val browserChannel = Channel<String>(Channel.BUFFERED)
    val openInBrowser = browserChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val lastUrl = sessionStore.lastServerUrl.first() ?: return@launch
            _state.update { if (it.serverUrl.isEmpty()) it.copy(serverUrl = lastUrl) else it }
        }
        viewModelScope.launch { ssoRedirects.collect(::finishSso) }
    }

    fun onServerUrlChange(value: String) = _state.update { it.copy(serverUrl = value, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun changeServer() = _state.update { it.copy(authConfig = null, error = null) }

    fun connect() {
        val current = _state.value
        if (!current.canConnect) return
        run {
            val config = client.authConfig(current.serverUrl)
            _state.update { it.copy(serverUrl = normalizeServerUrl(it.serverUrl), authConfig = config) }
        }
    }

    fun login() {
        val current = _state.value
        if (!current.canLogin) return
        run { sessionStore.save(client.login(current.serverUrl, current.username, current.password)) }
    }

    fun startSso() {
        val serverUrl = _state.value.serverUrl
        viewModelScope.launch {
            val verifier = Pkce.newVerifier()
            sessionStore.startSso(PendingSso(normalizeServerUrl(serverUrl), verifier))
            browserChannel.send(client.ssoStartUrl(serverUrl, Pkce.challenge(verifier)))
        }
    }

    private fun finishSso(uri: Uri) {
        run {
            val pending = sessionStore.takePendingSso() ?: error("Kein SSO-Login offen, bitte erneut starten")
            uri.getQueryParameter("error")?.let { error(it) }
            val code = uri.getQueryParameter("code") ?: error("Antwort ohne Anmeldecode")
            sessionStore.save(client.ssoExchange(pending.serverUrl, code, pending.codeVerifier))
        }
    }

    /** Führt [block] mit Ladeanzeige aus und übersetzt Fehler in eine Meldung. */
    private fun run(block: suspend () -> Unit) {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.toMessage()) }
            }
        }
    }

    private fun Exception.toMessage() = when {
        this is ClientRequestException && response.status == HttpStatusCode.Unauthorized ->
            "Benutzername oder Passwort falsch"
        this is ClientRequestException && response.status == HttpStatusCode.NotFound ->
            "Unter dieser Adresse läuft kein ASMR-Server"
        this is ClientRequestException -> "Server antwortet mit ${response.status.value}"
        this is IOException -> "Server nicht erreichbar"
        else -> message ?: "Anmeldung fehlgeschlagen"
    }
}
