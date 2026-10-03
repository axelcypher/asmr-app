package de.axelcypher.asmr.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Session(val serverUrl: String, val username: String, val token: String)

/** Ein begonnener SSO-Login; überlebt, falls Android die App während des Browser-Logins beendet. */
data class PendingSso(val serverUrl: String, val codeVerifier: String)

/** Hält Server-Verbindung und Token. Ohne gespeicherte Session ist der Nutzer abgemeldet. */
class SessionStore(private val dataStore: DataStore<Preferences>) {

    val session: Flow<Session?> = dataStore.data.map { prefs ->
        val serverUrl = prefs[SERVER_URL] ?: return@map null
        val token = prefs[TOKEN] ?: return@map null
        // Ältere Logins konnten "Https://…" gespeichert haben, siehe normalizeServerUrl.
        Session(de.axelcypher.asmr.data.api.normalizeServerUrl(serverUrl), prefs[USERNAME].orEmpty(), token)
    }

    /** Zuletzt verwendete Server-URL, bleibt auch nach dem Abmelden erhalten. */
    val lastServerUrl: Flow<String?> = dataStore.data.map { it[SERVER_URL] }

    suspend fun current(): Session? = session.first()

    suspend fun save(session: Session) {
        dataStore.edit {
            it[SERVER_URL] = session.serverUrl
            it[USERNAME] = session.username
            it[TOKEN] = session.token
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(TOKEN) }
    }

    suspend fun startSso(pending: PendingSso) {
        dataStore.edit {
            it[SSO_SERVER_URL] = pending.serverUrl
            it[SSO_VERIFIER] = pending.codeVerifier
        }
    }

    /** Liefert den offenen SSO-Login und vergisst ihn dabei (der Verifier gilt nur einmal). */
    suspend fun takePendingSso(): PendingSso? {
        var pending: PendingSso? = null
        dataStore.edit {
            val serverUrl = it[SSO_SERVER_URL]
            val verifier = it[SSO_VERIFIER]
            if (serverUrl != null && verifier != null) pending = PendingSso(serverUrl, verifier)
            it.remove(SSO_SERVER_URL)
            it.remove(SSO_VERIFIER)
        }
        return pending
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val TOKEN = stringPreferencesKey("token")
        val SSO_SERVER_URL = stringPreferencesKey("sso_server_url")
        val SSO_VERIFIER = stringPreferencesKey("sso_verifier")
    }
}
