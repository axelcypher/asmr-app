package de.axelcypher.asmr.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.data.settings.PlaybackSettings
import de.axelcypher.asmr.data.settings.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

data class AmbientState(val item: ItemDto, val volume: Float, val enabled: Boolean)

/** Ein laufender Sleep-Timer: um [endsAt] (Uhrzeit in ms) ist alles still. */
data class SleepTimerState(val endsAt: Long, val fadeMillis: Long, val isFading: Boolean = false)

data class PlayerState(
    val queue: List<ItemDto> = emptyList(),
    val current: ItemDto? = null,
    val isPlaying: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
)

/**
 * Zwei Player: die Hauptspur (steckt in der MediaSession, also Lockscreen und Benachrichtigung)
 * und eine Ambient-Spur im Endlos-Loop mit eigener Lautstärke. Die Ambient-Spur folgt Play/Pause
 * der Hauptspur und läuft weiter, wenn die Hauptspur zu Ende ist. Der Sleep-Timer blendet beide aus.
 *
 * Lebt so lange wie der Prozess; alle Aufrufe auf dem Main-Thread.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    context: Context,
    private val sessionStore: SessionStore,
    val settings: PlaybackSettings,
    /** Liest heruntergeladene Tracks lokal, alles andere vom Server (mit Session-Token). */
    val dataSourceFactory: DataSource.Factory,
) {
    private val scope: CoroutineScope = MainScope()

    val mainPlayer: ExoPlayer = buildPlayer(context, handleAudioFocus = true)
        .apply { setHandleAudioBecomingNoisy(true) }

    // Kein eigener Audio-Fokus, sonst würden sich die beiden Player gegenseitig pausieren.
    private val ambientPlayer: ExoPlayer = buildPlayer(context, handleAudioFocus = false)
        .apply { repeatMode = Player.REPEAT_MODE_ONE }

    /**
     * Großer Puffer (Audio ist klein) überbrückt WLAN-Löcher; die Retry-Policy gibt bei Netzwerkfehlern
     * nicht auf, und [recoverOnError] startet nach einem Fehler trotzdem an derselben Stelle neu.
     */
    private fun buildPlayer(context: Context, handleAudioFocus: Boolean): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(PatientLoadErrorPolicy()),
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, 2_500, 5_000)
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .setAudioAttributes(audioAttributes(), handleAudioFocus)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .build()
        .also(::recoverOnError)

    /** Nach Netzwerkfehlern mit wachsender Pause (bis 30 s) an derselben Stelle neu ansetzen. */
    private fun recoverOnError(player: ExoPlayer) {
        var attempts = 0
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!isRecoverable(error.errorCode)) return
                attempts++
                scope.launch {
                    delay((attempts * 5_000L).coerceAtMost(30_000L))
                    if (player.playerError != null) {
                        player.prepare()
                        player.play()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) attempts = 0
            }
        })
    }

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player

    private val _ambient = MutableStateFlow<AmbientState?>(null)
    val ambient: StateFlow<AmbientState?> = _ambient

    private val _sleepTimer = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = _sleepTimer

    private var timerJob: Job? = null

    init {
        mainPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = syncState()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncAmbient()
        })
        scope.launch {
            val stored = settings.ambient.first() ?: return@launch
            _ambient.value = AmbientState(stored.item, stored.volume, enabled = false)
        }
    }

    // --- Hauptspur ------------------------------------------------------------------------------

    fun play(queue: List<ItemDto>, startIndex: Int) {
        scope.launch {
            val session = sessionStore.current() ?: return@launch
            mainPlayer.setMediaItems(queue.map { it.toMediaItem(session.serverUrl) }, startIndex, 0L)
            mainPlayer.prepare()
            mainPlayer.play()
            _player.update { it.copy(queue = queue) }
            syncState()
        }
    }

    fun togglePlay() {
        if (mainPlayer.isPlaying) {
            mainPlayer.pause()
        } else {
            if (mainPlayer.playbackState == Player.STATE_ENDED) mainPlayer.seekToDefaultPosition()
            mainPlayer.play()
        }
    }

    fun seekBack() = mainPlayer.seekBack()
    fun seekForward() = mainPlayer.seekForward()
    fun seekTo(positionMs: Long) = mainPlayer.seekTo(positionMs)
    fun next() = mainPlayer.seekToNextMediaItem()
    fun previous() = mainPlayer.seekToPreviousMediaItem()

    /** Aus → alle → einzeln → aus. */
    fun cycleRepeatMode() {
        mainPlayer.repeatMode = when (mainPlayer.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() {
        mainPlayer.shuffleModeEnabled = !mainPlayer.shuffleModeEnabled
    }

    val positionMs get() = mainPlayer.currentPosition
    val durationMs get() = mainPlayer.duration.takeIf { it != C.TIME_UNSET }

    // --- Ambient-Spur ---------------------------------------------------------------------------

    fun setAmbient(item: ItemDto) {
        val volume = _ambient.value?.volume ?: DEFAULT_AMBIENT_VOLUME
        _ambient.value = AmbientState(item, volume, enabled = true)
        scope.launch {
            settings.saveAmbient(item, volume)
            loadAmbient(item)
            syncAmbient()
        }
    }

    fun toggleAmbient() {
        val current = _ambient.value ?: return
        _ambient.value = current.copy(enabled = !current.enabled)
        scope.launch {
            if (current.enabled.not() && ambientPlayer.mediaItemCount == 0) loadAmbient(current.item)
            syncAmbient()
        }
    }

    fun setAmbientVolume(volume: Float) {
        val current = _ambient.value ?: return
        _ambient.value = current.copy(volume = volume)
        if (_sleepTimer.value?.isFading != true) ambientPlayer.volume = volume
        scope.launch { settings.saveAmbient(current.item, volume) }
    }

    fun clearAmbient() {
        _ambient.value = null
        ambientPlayer.stop()
        ambientPlayer.clearMediaItems()
        scope.launch { settings.clearAmbient() }
    }

    private suspend fun loadAmbient(item: ItemDto) {
        val session = sessionStore.current() ?: return
        ambientPlayer.setMediaItem(item.toMediaItem(session.serverUrl))
        ambientPlayer.prepare()
    }

    /** Ambient läuft, solange die Hauptspur abspielen will (auch nach deren Ende). */
    private fun syncAmbient() {
        val ambient = _ambient.value
        ambientPlayer.playWhenReady = ambient?.enabled == true && mainPlayer.playWhenReady
        if (_sleepTimer.value?.isFading != true) ambientPlayer.volume = ambient?.volume ?: 0f
    }

    // --- Sleep-Timer ----------------------------------------------------------------------------

    fun startSleepTimer(duration: Duration) {
        scope.launch {
            val fade = minOf(settings.fadeMinutes.first().minutes, duration)
            schedule(System.currentTimeMillis() + duration.inWholeMilliseconds, fade.inWholeMilliseconds)
        }
    }

    /** Endet mit dem aktuellen Titel (bei Loop auf einzeln: nach dem aktuellen Durchlauf). */
    fun sleepAtEndOfTrack() {
        val remaining = (durationMs ?: return) - positionMs
        if (remaining > 0) startSleepTimer(remaining.milliseconds)
    }

    fun extendSleepTimer(by: Duration = 10.minutes) {
        val timer = _sleepTimer.value ?: return
        restoreVolumes()
        schedule(maxOf(timer.endsAt, System.currentTimeMillis()) + by.inWholeMilliseconds, timer.fadeMillis)
    }

    fun cancelSleepTimer() {
        timerJob?.cancel()
        _sleepTimer.value = null
        restoreVolumes()
    }

    private fun schedule(endsAt: Long, fadeMillis: Long) {
        timerJob?.cancel()
        _sleepTimer.value = SleepTimerState(endsAt, fadeMillis)
        timerJob = scope.launch {
            delay(endsAt - fadeMillis - System.currentTimeMillis())
            _sleepTimer.update { it?.copy(isFading = true) }
            val mainStart = mainPlayer.volume
            val ambientStart = ambientPlayer.volume
            while (isActive) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0) break
                val factor = left.toFloat() / fadeMillis.coerceAtLeast(1)
                mainPlayer.volume = mainStart * factor
                ambientPlayer.volume = ambientStart * factor
                delay(FADE_STEP_MS)
            }
            mainPlayer.pause()
            ambientPlayer.pause()
            _sleepTimer.value = null
            restoreVolumes()
        }
    }

    private fun restoreVolumes() {
        mainPlayer.volume = 1f
        ambientPlayer.volume = _ambient.value?.volume ?: 0f
    }

    // --- Intern ---------------------------------------------------------------------------------


    private fun syncState() {
        val queue = _player.value.queue
        val currentId = mainPlayer.currentMediaItem?.mediaId
        _player.update {
            it.copy(
                current = queue.firstOrNull { item -> item.id.toString() == currentId },
                isPlaying = mainPlayer.isPlaying,
                repeatMode = mainPlayer.repeatMode,
                shuffle = mainPlayer.shuffleModeEnabled,
            )
        }
    }

    private fun ItemDto.toMediaItem(serverUrl: String) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(AsmrClient.audioUrl(serverUrl, id).toUri())
        .setCustomCacheKey(OfflineStore.cacheKey(id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(creator)
                .setArtworkUri(if (hasCover) AsmrClient.coverUrl(serverUrl, id).toUri() else null)
                .build(),
        )
        .build()

    private companion object {
        const val SEEK_STEP_MS = 15_000L
        const val MIN_BUFFER_MS = 60_000
        const val MAX_BUFFER_MS = 30 * 60_000
        const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024
        const val FADE_STEP_MS = 250L
        const val DEFAULT_AMBIENT_VOLUME = 0.5f

        fun audioAttributes() = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
