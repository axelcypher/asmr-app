package de.axelcypher.asmr.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.axelcypher.asmr.playback.PlaybackEngine
import de.axelcypher.asmr.ui.AppIcons
import de.axelcypher.asmr.ui.library.Cover

enum class Tab { Library, Profile }

/** Menüleiste: Bibliothek, in der Mitte der laufende Track (öffnet den Player), Profil. */
@Composable
fun NowPlayingBar(
    engine: PlaybackEngine,
    coverUrl: (Long) -> String?,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val player by engine.player.collectAsStateWithLifecycle()
    val timer by engine.sleepTimer.collectAsStateWithLifecycle()
    val item = player.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
            .height(72.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabButton(AppIcons.Library, "Bibliothek", selected = tab == Tab.Library) { onTab(Tab.Library) }

        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = item != null, onClick = onOpenPlayer)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(item?.let { coverUrl(it.id) }, AppIcons.Headphones, Modifier.size(44.dp), RoundedCornerShape(10.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    item?.title ?: "Nichts läuft",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (item == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val sub = timer?.let { sleepLabel(it.endsAt, it.isFading) } ?: item?.creator
                sub?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item != null) {
                IconButton(onClick = engine::togglePlay) {
                    Icon(
                        if (player.isPlaying) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (player.isPlaying) "Pause" else "Abspielen",
                    )
                }
            }
        }

        TabButton(AppIcons.Person, "Profil", selected = tab == Tab.Profile) { onTab(Tab.Profile) }
    }
}

@Composable
private fun TabButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
