package de.axelcypher.asmr.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Session(
    val serverUrl: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String?,
)

/** Hält Server-Verbindung und Tokens. Ohne gespeicherte Session ist der Nutzer abgemeldet. */
class SessionStore(private val dataStore: DataStore<Preferences>) {

    val session: Flow<Session?> = dataStore.data.map { prefs ->
        val serverUrl = prefs[SERVER_URL] ?: return@map null
        val accessToken = prefs[ACCESS_TOKEN] ?: return@map null
        Session(serverUrl, prefs[USERNAME].orEmpty(), accessToken, prefs[REFRESH_TOKEN])
    }

    val selectedLibraryId: Flow<String?> = dataStore.data.map { it[LIBRARY_ID] }

    /** Zuletzt verwendete Server-URL, bleibt auch nach dem Abmelden erhalten. */
    val lastServerUrl: Flow<String?> = dataStore.data.map { it[SERVER_URL] }

    suspend fun current(): Session? = session.first()

    suspend fun save(session: Session) {
        dataStore.edit {
            it[SERVER_URL] = session.serverUrl
            it[USERNAME] = session.username
            it[ACCESS_TOKEN] = session.accessToken
            it.setOrRemove(REFRESH_TOKEN, session.refreshToken)
        }
    }

    suspend fun updateTokens(accessToken: String, refreshToken: String?) {
        dataStore.edit {
            it[ACCESS_TOKEN] = accessToken
            it.setOrRemove(REFRESH_TOKEN, refreshToken)
        }
    }

    suspend fun selectLibrary(libraryId: String) {
        dataStore.edit { it[LIBRARY_ID] = libraryId }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(ACCESS_TOKEN)
            it.remove(REFRESH_TOKEN)
            it.remove(LIBRARY_ID)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.setOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value != null) this[key] = value else remove(key)
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val LIBRARY_ID = stringPreferencesKey("library_id")
    }
}
