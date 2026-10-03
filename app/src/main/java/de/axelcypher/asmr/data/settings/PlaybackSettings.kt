package de.axelcypher.asmr.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.axelcypher.asmr.api.ApiJson
import de.axelcypher.asmr.api.ItemDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class StoredAmbient(val item: ItemDto, val volume: Float)

enum class UpdateChannel { STABLE, NIGHTLY }

/** Einstellungen, die beim nächsten Einschlafen wieder da sein sollen. */
class PlaybackSettings(private val dataStore: DataStore<Preferences>) {

    val ambient: Flow<StoredAmbient?> = dataStore.data.map { prefs ->
        val json = prefs[AMBIENT_ITEM] ?: return@map null
        val item = runCatching { ApiJson.decodeFromString<ItemDto>(json) }.getOrNull() ?: return@map null
        StoredAmbient(item, prefs[AMBIENT_VOLUME] ?: 0.5f)
    }

    val fadeMinutes: Flow<Int> = dataStore.data.map { it[FADE_MINUTES] ?: DEFAULT_FADE_MINUTES }

    val updateChannel: Flow<UpdateChannel> = dataStore.data.map { prefs ->
        prefs[UPDATE_CHANNEL]?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() } ?: UpdateChannel.STABLE
    }

    suspend fun saveAmbient(item: ItemDto, volume: Float) {
        dataStore.edit {
            it[AMBIENT_ITEM] = ApiJson.encodeToString(ItemDto.serializer(), item)
            it[AMBIENT_VOLUME] = volume
        }
    }

    suspend fun clearAmbient() {
        dataStore.edit { it.remove(AMBIENT_ITEM) }
    }

    suspend fun setFadeMinutes(minutes: Int) {
        dataStore.edit { it[FADE_MINUTES] = minutes }
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        dataStore.edit { it[UPDATE_CHANNEL] = channel.name }
    }

    private companion object {
        const val DEFAULT_FADE_MINUTES = 3
        val AMBIENT_ITEM = stringPreferencesKey("ambient_item")
        val AMBIENT_VOLUME = floatPreferencesKey("ambient_volume")
        val FADE_MINUTES = intPreferencesKey("fade_minutes")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
    }
}
