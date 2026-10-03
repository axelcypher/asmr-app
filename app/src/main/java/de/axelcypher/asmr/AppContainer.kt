package de.axelcypher.asmr

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.SessionStore
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** Einfache manuelle Dependency Injection, lebt so lange wie die [AsmrApp]. */
class AppContainer(context: Context) {
    val sessionStore = SessionStore(context.sessionDataStore)
    val asmrClient = AsmrClient(OkHttp.create(), sessionStore)

    /** Redirects vom SSO-Login (`de.axelcypher.asmr://sso?...`), von der MainActivity eingespeist. */
    private val ssoRedirectChannel = Channel<Uri>(Channel.CONFLATED)
    val ssoRedirects = ssoRedirectChannel.receiveAsFlow()

    fun onSsoRedirect(uri: Uri) {
        ssoRedirectChannel.trySend(uri)
    }
}
