package de.axelcypher.asmr.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.axelcypher.asmr.api.AccessOverviewDto
import de.axelcypher.asmr.api.ItemDto

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
