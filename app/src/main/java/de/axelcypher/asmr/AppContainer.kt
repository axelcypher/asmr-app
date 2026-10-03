package de.axelcypher.asmr

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.PlaybackSettings
import de.axelcypher.asmr.data.settings.SessionStore
import de.axelcypher.asmr.playback.OfflineStore
import de.axelcypher.asmr.playback.PlaybackEngine
import de.axelcypher.asmr.update.AppUpdater
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private val Context.sessionDataStore by preferencesDataStore(name = "session")
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Einfache manuelle Dependency Injection, lebt so lange wie die [AsmrApp]. */
@OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    private val scope = MainScope()

    val sessionStore = SessionStore(context.sessionDataStore)
    val playbackSettings = PlaybackSettings(context.settingsDataStore)
    val asmrClient = AsmrClient(OkHttp.create(), sessionStore)
    val appUpdater = AppUpdater(context)

    /** HTTP für Audio, Cover-Bitmaps und Downloads; das Session-Token wird unten aktuell gehalten. */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    val offline = OfflineStore(context, asmrClient.httpClient, httpDataSourceFactory)

    val playbackEngine by lazy {
        PlaybackEngine(context, sessionStore, playbackSettings, offline.playbackDataSourceFactory)
    }

    /** Redirects vom SSO-Login (`de.axelcypher.asmr://sso?...`), von der MainActivity eingespeist. */
    private val ssoRedirectChannel = Channel<Uri>(Channel.CONFLATED)
    val ssoRedirects = ssoRedirectChannel.receiveAsFlow()

    /** Per "Teilen" an die App geschickte URL, die importiert werden soll. */
    val sharedUrl = MutableStateFlow<String?>(null)

    /** Aktuelle Session für Stellen ohne Coroutine (OkHttp-Interceptor des Bildladers). */
    @Volatile
    var currentSession: de.axelcypher.asmr.data.settings.Session? = null
        private set

    init {
        scope.launch {
            sessionStore.session.collect { session ->
                currentSession = session
                httpDataSourceFactory.setDefaultRequestProperties(
                    session?.let { mapOf("Authorization" to "Bearer ${it.token}") } ?: emptyMap(),
                )
            }
        }
    }

    /** Letzte Bildfehler (neueste zuerst), für die Diagnose im Profil. */
    val imageErrors = MutableStateFlow<List<String>>(emptyList())

    fun reportImageError(message: String) {
        imageErrors.value = (listOf(message) + imageErrors.value).take(5)
    }

    fun onSsoRedirect(uri: Uri) {
        ssoRedirectChannel.trySend(uri)
    }
}
