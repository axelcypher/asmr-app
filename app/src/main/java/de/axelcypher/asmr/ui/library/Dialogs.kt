package de.axelcypher.asmr.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.axelcypher.asmr.api.AccessOverviewDto
import coil3.compose.AsyncImage
import de.axelcypher.asmr.api.ItemDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NewFolderDialog(parent: String, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neuer Ordner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In: ${parent.ifEmpty { "Bibliothek" }}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank() && '/' !in name) { Text("Anlegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Zielordner wählen; "" ist die oberste Ebene. */
@Composable
fun MoveDialog(item: ItemDto, folders: List<String>, onMove: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(item.folder) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verschieben") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(listOf("") + folders) { folder ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = folder }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == folder, onClick = { selected = folder })
                        Text(folder.ifEmpty { "Bibliothek (oberste Ebene)" })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(selected) }, enabled = selected != item.folder) { Text("Verschieben") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun ConfirmDeleteDialog(item: ItemDto, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Löschen?") },
        text = { Text("\"${item.title}\" wird mit Datei, eigenem Bild und Metadaten vom NAS gelöscht.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Zugriff auf einen Ordner (samt Unterordnern): ohne Einschränkung sehen ihn alle, sonst nur die
 * gewählten SSO-Gruppen und Benutzer. Admins sehen immer alles.
 */
@Composable
fun AccessDialog(
    path: String,
    overview: AccessOverviewDto?,
    onSave: (restricted: Boolean, groups: List<String>, userIds: List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (overview == null) {
        AlertDialog(onDismissRequest = onDismiss, confirmButton = {}, text = { Text("Lädt …") })
        return
    }
    val rule = overview.rules.firstOrNull { it.path == path }
    var restricted by remember(rule) { mutableStateOf(rule != null) }
    val groups = remember(rule) { mutableStateListOf<String>().apply { addAll(rule?.groups.orEmpty()) } }
    val userIds = remember(rule) { mutableStateListOf<Long>().apply { addAll(rule?.userIds.orEmpty()) } }
    var newGroup by remember { mutableStateOf("") }
    val knownGroups = (overview.groups + groups).distinctBy(String::lowercase).sortedBy(String::lowercase)
    val inherited = overview.rules.filter { path.startsWith("${it.path}/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zugriff: ${path.substringAfterLast('/')}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (inherited.isNotEmpty()) {
                    Text(
                        "Zusätzlich gelten die Einschränkungen von: ${inherited.joinToString { it.path }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Eingeschränkt", modifier = Modifier.weight(1f))
                    Switch(checked = restricted, onCheckedChange = { restricted = it })
                }
                if (restricted) {
                    Text("SSO-Gruppen", style = MaterialTheme.typography.titleSmall)
                    if (!overview.ssoEnabled) {
                        Text(
                            "SSO ist nicht eingerichtet; Gruppen greifen erst, wenn sich Benutzer per SSO anmelden.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    knownGroups.forEach { group ->
                        CheckRow(group, checked = groups.any { it.equals(group, true) }) { checked ->
                            if (checked) groups.add(group) else groups.removeAll { it.equals(group, true) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            newGroup, { newGroup = it },
                            label = { Text("Gruppe hinzufügen") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { groups.add(newGroup.trim()); newGroup = "" }, enabled = newGroup.isNotBlank()) {
                            Text("+")
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Benutzer", style = MaterialTheme.typography.titleSmall)
                    overview.users.filterNot { it.isAdmin }.forEach { user ->
                        CheckRow(user.displayName ?: user.username, checked = user.id in userIds) { checked ->
                            if (checked) userIds.add(user.id) else userIds.remove(user.id)
                        }
                    }
                    if (overview.users.none { !it.isAdmin }) {
                        Text("Keine weiteren Benutzer", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(restricted, groups.toList(), userIds.toList()) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Bestehende eigene Playlist wählen oder eine neue anlegen. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<de.axelcypher.asmr.api.PlaylistDto>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zu Playlist hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                playlists.filter { it.isMine }.forEach { playlist ->
                    Text(
                        playlist.name,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(playlist.id) }.padding(vertical = 10.dp),
                    )
                }
                OutlinedTextField(newName, { newName = it }, label = { Text("Neue Playlist") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(newName.trim()) }, enabled = newName.isNotBlank()) { Text("Anlegen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Was mit dem Bild einer Ambiente-Kategorie passieren soll. */
sealed interface ImageChange {
    class Replace(val bytes: ByteArray) : ImageChange
    data object Remove : ImageChange
}

/**
 * Kategorie anlegen ([existing] null) oder bearbeiten, mit Icon- und Farbwahl. Ambiente-Kategorien
 * ([ambient], fest je nach Ort des Anlegens) bekommen zusätzlich ein eigenes Bild für ihre Kachel.
 */
@Composable
fun CategoryDialog(
    existing: de.axelcypher.asmr.api.CategoryDto?,
    ambient: Boolean,
    imageUrl: String?,
    onSave: (de.axelcypher.asmr.api.CategoryRequest, ImageChange?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var icon by remember { mutableStateOf(existing?.icon ?: de.axelcypher.asmr.api.CATEGORY_ICONS.first()) }
    var color by remember { mutableStateOf(existing?.color ?: de.axelcypher.asmr.api.CATEGORY_COLORS.first()) }
    var image by remember { mutableStateOf<ImageChange?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null) image = ImageChange.Replace(bytes)
        }
    }
    val kind = if (ambient) "Ambiente" else "Kategorie"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Neue${if (ambient) "s" else ""} $kind" else "$kind bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                if (ambient) {
                    val preview: Any? = when (val change = image) {
                        is ImageChange.Replace -> change.bytes
                        ImageChange.Remove -> null
                        null -> imageUrl
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(parseColor(color)),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(de.axelcypher.asmr.ui.AppIcons.category(icon), null, tint = Color.White)
                            if (preview != null) {
                                AsyncImage(model = preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                            }
                        }
                        Column {
                            TextButton(onClick = {
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text("Bild wählen") }
                            if (preview != null) TextButton(onClick = { image = ImageChange.Remove }) { Text("Bild entfernen") }
                        }
                    }
                    Text(
                        "Ohne eigenes Bild zeigt die Kachel das Cover des ersten Sounds, sonst Icon und Farbe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Icon", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    de.axelcypher.asmr.api.CATEGORY_ICONS.forEach { key ->
                        val selected = key == icon
                        androidx.compose.material3.IconButton(
                            onClick = { icon = key },
                            colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                        ) { androidx.compose.material3.Icon(de.axelcypher.asmr.ui.AppIcons.category(key), key) }
                    }
                }
                Text("Farbe", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    de.axelcypher.asmr.api.CATEGORY_COLORS.forEach { hex ->
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .padding(bottom = 8.dp)
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(parseColor(hex))
                                .clickable { color = hex },
                            contentAlignment = Alignment.Center,
                        ) { if (hex == color) Text("✓", color = androidx.compose.ui.graphics.Color.White) }
                    }
                }
                if (existing != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Wirklich löschen?" else "$kind löschen", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(de.axelcypher.asmr.api.CategoryRequest(name.trim(), icon, color, ambient), image) },
                enabled = name.isNotBlank(),
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** In welchen Kategorien ein Track bzw. Ordner steckt. */
@Composable
fun MembershipDialog(
    title: String,
    categories: List<de.axelcypher.asmr.api.CategoryDto>,
    initial: Set<Long>,
    onSave: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(initial) { mutableStateListOf<Long>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (categories.isEmpty()) Text("Noch keine Kategorien. Auf der Übersicht unter \"Kategorien\" anlegen.")
                val (ambient, normal) = categories.partition { it.isAmbient }
                listOf("Kategorien" to normal, "Ambiente" to ambient).filter { it.second.isNotEmpty() }.forEach { (heading, group) ->
                    Text(
                        heading,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    group.forEach { category ->
                        CheckRow(category.name, checked = category.id in selected) { checked ->
                            if (checked) selected.add(category.id) else selected.remove(category.id)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected.toSet()) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
