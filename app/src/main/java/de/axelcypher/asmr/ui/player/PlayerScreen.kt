package de.axelcypher.asmr.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.playback.PlaybackEngine
import de.axelcypher.asmr.ui.AppIcons
import de.axelcypher.asmr.ui.TriggerChips
import de.axelcypher.asmr.ui.formatDuration
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    engine: PlaybackEngine,
    serverUrl: String,
    onClose: () -> Unit,
    onCreator: (String) -> Unit,
    onFavorite: (de.axelcypher.asmr.api.ItemDto) -> Unit,
) {
    val player by engine.player.collectAsStateWithLifecycle()
    val ambient by engine.ambient.collectAsStateWithLifecycle()
    val timer by engine.sleepTimer.collectAsStateWithLifecycle()
    var showTimerSheet by remember { mutableStateOf(false) }
    var blackScreen by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(engine) {
        while (true) {
            position = engine.positionMs
            duration = engine.durationMs ?: 0L
            delay(500)
        }
    }

    val item = player.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(AppIcons.ArrowDown, contentDescription = "Zur Bibliothek") }
        }

        Box(
            Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (item?.hasCover == true) {
                AsyncImage(
                    model = AsmrClient.coverUrl(serverUrl, item.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text(
            item?.title ?: "Nichts ausgewählt",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        item?.creator?.let { creator ->
            TextButton(onClick = { onCreator(creator) }) {
                Text(creator, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        item?.let { current ->
            IconButton(onClick = { onFavorite(current) }) {
                Icon(
                    if (current.isFavorite) AppIcons.Heart else AppIcons.HeartOutline,
                    contentDescription = if (current.isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                    tint = if (current.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TriggerChips(current.levels, Modifier.fillMaxWidth())
        }

        SeekBar(position, duration, onSeek = engine::seekTo)

        // Hauptsteuerung, symmetrisch um Play/Pause.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = engine::previous) { Icon(AppIcons.SkipPrevious, "Vorheriger Titel") }
            SeekStepButton(AppIcons.Replay, "15 Sekunden zurück", onClick = engine::seekBack)
            FilledIconButton(onClick = engine::togglePlay, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (player.isPlaying) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (player.isPlaying) "Pause" else "Abspielen",
                    modifier = Modifier.size(40.dp),
                )
            }
            SeekStepButton(AppIcons.Forward, "15 Sekunden vor", onClick = engine::seekForward)
            IconButton(onClick = engine::next) { Icon(AppIcons.SkipNext, "Nächster Titel") }
        }

        // Modi in eigener Zeile, jeweils Icon mit Beschriftung.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ModeButton(AppIcons.Shuffle, "Shuffle", active = player.shuffle, onClick = engine::toggleShuffle)
            ModeButton(
                icon = if (player.repeatMode == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                label = when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Loop: Titel"
                    Player.REPEAT_MODE_ALL -> "Loop: Alle"
                    else -> "Loop"
                },
                active = player.repeatMode != Player.REPEAT_MODE_OFF,
                onClick = engine::cycleRepeatMode,
            )
            ModeButton(
                AppIcons.Timer,
                label = timer?.let { sleepLabel(it.endsAt, it.isFading).removePrefix("Stopp in ") } ?: "Timer",
                active = timer != null,
                onClick = { showTimerSheet = true },
            )
            ModeButton(AppIcons.Bedtime, "Schwarz", active = false, onClick = { blackScreen = true })
        }

        AmbientCard(engine, ambient)
        Spacer(Modifier.height(16.dp))
    }

    if (showTimerSheet) SleepTimerSheet(engine, onDismiss = { showTimerSheet = false })
    if (blackScreen) BlackScreen(onExit = { blackScreen = false })
}

@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (duration > 0) position.toFloat() / duration else 0f
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = if (dragging) dragValue else fraction.coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(position / 1000.0), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(duration / 1000.0), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Kreis-Pfeil mit "15" in der Mitte, wie in gängigen Podcast-Playern. */
@Composable
private fun SeekStepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(36.dp))
            Text("15", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
private fun AmbientCard(engine: PlaybackEngine, ambient: de.axelcypher.asmr.playback.AmbientState?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Zweite Spur (Ambient)", style = MaterialTheme.typography.titleSmall)
            if (ambient == null) {
                Text(
                    "In der Bibliothek ein Item lange antippen und \"Als Ambient-Spur\" wählen, z.B. Regen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ambient.item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = ambient.enabled, onCheckedChange = { engine.toggleAmbient() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lautstärke", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = ambient.volume,
                        onValueChange = engine::setAmbientVolume,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
                TextButton(onClick = engine::clearAmbient, modifier = Modifier.align(Alignment.End)) {
                    Text("Entfernen")
                }
            }
        }
    }
}

/** Komplett schwarz (OLED aus), Bildschirm bleibt an; doppelt tippen beendet. */
@Composable
private fun BlackScreen(onExit: () -> Unit) {
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = onExit)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onExit() }) },
    ) {
        Text(
            "Doppelt tippen zum Beenden",
            color = Color(0xFF1A1A1A),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
        )
    }
}
