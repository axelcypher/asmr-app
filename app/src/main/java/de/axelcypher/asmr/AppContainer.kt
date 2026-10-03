package de.axelcypher.asmr

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.PlaybackSettings
import de.axelcypher.asmr.data.settings.SessionStore
import de.axelcypher.asmr.playback.PlaybackEngine
import de.axelcypher.asmr.update.AppUpdater
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

private val Context.sessionDataStore by preferencesDataStore(name = "session")
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Einfache manuelle Dependency Injection, lebt so lange wie die [AsmrApp]. */
class AppContainer(context: Context) {
    val sessionStore = SessionStore(context.sessionDataStore)
    val playbackSettings = PlaybackSettings(context.settingsDataStore)
    val asmrClient = AsmrClient(OkHttp.create(), sessionStore)
    val playbackEngine by lazy { PlaybackEngine(context, sessionStore, playbackSettings) }
    val appUpdater = AppUpdater(context)

    /** Redirects vom SSO-Login (`de.axelcypher.asmr://sso?...`), von der MainActivity eingespeist. */
    private val ssoRedirectChannel = Channel<Uri>(Channel.CONFLATED)
    val ssoRedirects = ssoRedirectChannel.receiveAsFlow()

    /** Per "Teilen" an die App geschickte URL, die importiert werden soll. */
    val sharedUrl = MutableStateFlow<String?>(null)

    fun onSsoRedirect(uri: Uri) {
        ssoRedirectChannel.trySend(uri)
    }
}
