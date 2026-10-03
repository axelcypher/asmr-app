package de.axelcypher.asmr

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import de.axelcypher.asmr.data.api.AbsClient
import de.axelcypher.asmr.data.settings.SessionStore
import io.ktor.client.engine.okhttp.OkHttp

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** Einfache manuelle Dependency Injection, lebt so lange wie die [AsmrApp]. */
class AppContainer(context: Context) {
    val sessionStore = SessionStore(context.sessionDataStore)
    val absClient = AbsClient(OkHttp.create(), sessionStore)
}
