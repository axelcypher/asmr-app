package de.axelcypher.asmr.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.axelcypher.asmr.playback.PlaybackEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

private val PRESETS = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(engine: PlaybackEngine, onDismiss: () -> Unit) {
    val timer by engine.sleepTimer.collectAsStateWithLifecycle()
    val settings = engine.settings
    val fadeMinutes by settings.fadeMinutes.collectAsStateWithLifecycle(initialValue = 3)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sleep-Timer", style = MaterialTheme.typography.titleLarge)

            val active = timer
            if (active != null) {
                Text(sleepLabel(active.endsAt, active.isFading), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { engine.extendSleepTimer() }) { Text("+10 min") }
                    OutlinedButton(onClick = {
                        engine.cancelSleepTimer()
                        onDismiss()
                    }) { Text("Aus") }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { minutes ->
                    OutlinedButton(onClick = {
                        engine.startSleepTimer(minutes.minutes)
                        onDismiss()
                    }) { Text("$minutes min") }
                }
                OutlinedButton(onClick = {
                    engine.sleepAtEndOfTrack()
                    onDismiss()
                }) { Text("Ende des Titels") }
            }

            Text("Ausblenden über $fadeMinutes min", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = fadeMinutes.toFloat(),
                onValueChange = { scope.launch { settings.setFadeMinutes(it.roundToInt()) } },
                valueRange = 1f..5f,
                steps = 3,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.padding(bottom = 8.dp)) { Text("Schließen") }
        }
    }
}

/** "Stopp in 23 min" bzw. "Blendet aus …", sekundengenau aktualisiert. */
@Composable
fun sleepLabel(endsAt: Long, isFading: Boolean): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = ((endsAt - now) / 1000).coerceAtLeast(0)
    val text = if (left >= 60) "${(left + 59) / 60} min" else "$left s"
    return if (isFading) "Blendet aus, Stopp in $text" else "Stopp in $text"
}
