package de.axelcypher.asmr

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
import de.axelcypher.asmr.ui.library.LibraryScreen
import de.axelcypher.asmr.ui.library.LibraryViewModel
import de.axelcypher.asmr.ui.login.LoginScreen
import de.axelcypher.asmr.ui.login.LoginViewModel
import de.axelcypher.asmr.ui.theme.AsmrTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AsmrApp).container
        setContent {
            AsmrTheme {
                Surface(Modifier.fillMaxSize()) {
                    AsmrRoot(container)
                }
            }
        }
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
            viewModel { LoginViewModel(container.absClient, container.sessionStore) },
        )
        // Key pro Benutzer, damit nach einem Benutzerwechsel ein frisches ViewModel entsteht.
        is SessionState.LoggedIn -> LibraryScreen(
            viewModel(key = "library-${state.username}") {
                LibraryViewModel(container.absClient, container.sessionStore)
            },
        )
    }
}
