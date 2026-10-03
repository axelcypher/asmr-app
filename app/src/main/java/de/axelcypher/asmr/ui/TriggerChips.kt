package de.axelcypher.asmr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.axelcypher.asmr.api.MAX_TRIGGER_LEVEL

/** Trigger mit Stärke > 0 als kleine Chips mit Balken, stärkste zuerst. */
@Composable
fun TriggerChips(levels: Map<String, Int>, modifier: Modifier = Modifier) {
    val shown = levels.filterValues { it > 0 }.entries.sortedByDescending { it.value }
    if (shown.isEmpty()) return
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        shown.forEach { (trigger, level) ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(trigger, style = MaterialTheme.typography.labelMedium)
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .width(24.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(level.toFloat() / MAX_TRIGGER_LEVEL)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    level.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
