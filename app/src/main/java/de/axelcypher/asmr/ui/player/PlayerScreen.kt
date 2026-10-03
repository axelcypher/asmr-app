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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import de.axelcypher.asmr.ui.formatDuration
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(engine: PlaybackEngine, serverUrl: String, onClose: () -> Unit) {
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
            TextButton(onClick = onClose) { Text("↓ Bibliothek") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { blackScreen = true }) { Text("Schwarz") }
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
        item?.creator?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SeekBar(position, duration, onSeek = engine::seekTo)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlText(if (player.shuffle) "Shuffle an" else "Shuffle", active = player.shuffle, onClick = engine::toggleShuffle)
            ControlText("⏮", onClick = engine::previous)
            ControlText("-15", onClick = engine::seekBack)
            FilledIconButton(onClick = engine::togglePlay, modifier = Modifier.size(64.dp)) {
                Text(if (player.isPlaying) "⏸" else "▶", fontSize = 26.sp)
            }
            ControlText("+15", onClick = engine::seekForward)
            ControlText("⏭", onClick = engine::next)
            ControlText(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Loop 1"
                    Player.REPEAT_MODE_ALL -> "Loop alle"
                    else -> "Loop"
                },
                active = player.repeatMode != Player.REPEAT_MODE_OFF,
                onClick = engine::cycleRepeatMode,
            )
        }

        OutlinedButton(onClick = { showTimerSheet = true }, modifier = Modifier.fillMaxWidth()) {
            Text(timer?.let { sleepLabel(it.endsAt, it.isFading) } ?: "Sleep-Timer")
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

@Composable
private fun ControlText(label: String, active: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** Kleine Leiste über der Bibliothek, solange etwas geladen ist. */
@Composable
fun MiniPlayer(engine: PlaybackEngine, onOpen: () -> Unit) {
    val player by engine.player.collectAsStateWithLifecycle()
    val timer by engine.sleepTimer.collectAsStateWithLifecycle()
    val item = player.current ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                timer?.let { sleepLabel(it.endsAt, it.isFading) } ?: (item.creator ?: ""),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = engine::togglePlay) {
            Text(if (player.isPlaying) "⏸" else "▶", fontSize = 22.sp)
        }
    }
}
