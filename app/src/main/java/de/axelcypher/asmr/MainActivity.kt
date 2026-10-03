package de.axelcypher.asmr

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.axelcypher.asmr.api.APP_SSO_REDIRECT
import de.axelcypher.asmr.playback.PlaybackService
import de.axelcypher.asmr.ui.library.LibraryScreen
import de.axelcypher.asmr.ui.library.LibraryViewModel
import de.axelcypher.asmr.ui.login.LoginScreen
import de.axelcypher.asmr.ui.login.LoginViewModel
import de.axelcypher.asmr.ui.theme.AsmrTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val container get() = (application as AsmrApp).container

    /** Hält den Wiedergabe-Dienst gebunden, solange die App sichtbar ist. */
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AsmrTheme {
                Surface(Modifier.fillMaxSize()) {
                    AsmrRoot(container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { container.sharedUrl.value = it }
            return
        }
        val uri = intent.data ?: return
        if (uri.toString().startsWith(APP_SSO_REDIRECT)) container.onSsoRedirect(uri)
    }
}

private sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val username: String) : SessionState
}

@Composable
private fun AsmrRoot(container: AppContainer) {
    val sessionFlow = remember(container) {
        container.sessionStore.session.map { session ->
            if (session == null) SessionState.LoggedOut else SessionState.LoggedIn(session.username)
        }
    }
    val sessionState by sessionFlow.collectAsStateWithLifecycle(SessionState.Loading)

    when (val state = sessionState) {
        SessionState.Loading -> Unit
        SessionState.LoggedOut -> LoginScreen(
            viewModel { LoginViewModel(container.asmrClient, container.sessionStore, container.ssoRedirects) },
        )
        // Key pro Benutzer, damit nach einem Benutzerwechsel ein frisches ViewModel entsteht.
        is SessionState.LoggedIn -> LibraryScreen(
            viewModel(key = "library-${state.username}") {
                LibraryViewModel(container.asmrClient, container.sessionStore, container.playbackSettings, container.appUpdater)
            },
            container.playbackEngine,
            container.sharedUrl,
            container.asmrClient,
        )
    }
}
