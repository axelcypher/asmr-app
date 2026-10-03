package de.axelcypher.asmr.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.axelcypher.asmr.api.ItemDto
import de.axelcypher.asmr.api.MAX_TRIGGER_LEVEL
import de.axelcypher.asmr.api.TRIGGER_CATALOG
import de.axelcypher.asmr.api.UpdateItemRequest
import de.axelcypher.asmr.ui.AppIcons
import kotlin.math.roundToInt

/** Vollbild-Editor für Titel, Creator und die Bewertungsmatrix eines Tracks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditor(
    item: ItemDto,
    /** Gruppen mit Reglern; kommt vom Server, offline der eingebaute Katalog. */
    catalog: List<Pair<String, List<String>>> = TRIGGER_CATALOG,
    onSave: (UpdateItemRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(item.title) }
    var creator by remember { mutableStateOf(item.creator.orEmpty()) }
    val levels = remember { mutableStateMapOf<String, Int>().apply { putAll(item.levels) } }
    var newTrigger by remember { mutableStateOf("") }
    // Eigene Trigger, die nicht im Katalog stehen, bekommen eine eigene Gruppe.
    val custom = levels.keys.filter { key -> catalog.flatMap { it.second }.none { it.equals(key, ignoreCase = true) } }.sorted()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Bearbeiten") },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(AppIcons.ArrowBack, "Abbrechen") } },
                        actions = {
                            TextButton(
                                onClick = {
                                    onSave(
                                        UpdateItemRequest(
                                            title = title.trim().takeIf { it != item.title },
                                            creator = creator.trim().takeIf { it != item.creator.orEmpty() },
                                            levels = levels.filterValues { it > 0 }.takeIf { it != item.levels },
                                        ),
                                    )
                                },
                                enabled = title.isNotBlank(),
                            ) { Text("Speichern") }
                        },
                    )
                },
            ) { padding ->
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        OutlinedTextField(title, { title = it }, label = { Text("Titel") }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        OutlinedTextField(
                            creator,
                            { creator = it },
                            label = { Text("Creator") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                    item {
                        Text(
                            "Trigger-Stärke 0–$MAX_TRIGGER_LEVEL; 0 heißt \"kommt nicht vor\" und wird nicht angezeigt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    catalog.forEach { (group, triggers) ->
                        item(key = "group-$group") { GroupHeader(group) }
                        items(triggers, key = { it }) { trigger ->
                            LevelRow(trigger, levelOf(levels, trigger)) { setLevel(levels, trigger, it) }
                        }
                    }
                    item(key = "group-custom") { GroupHeader("Eigene") }
                    items(custom, key = { "custom-$it" }) { trigger ->
                        LevelRow(trigger, levels[trigger] ?: 0) { setLevel(levels, trigger, it) }
                    }
                    item(key = "add") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp)) {
                            OutlinedTextField(
                                newTrigger,
                                { newTrigger = it },
                                label = { Text("Eigener Trigger") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    levels[newTrigger.trim()] = 5
                                    newTrigger = ""
                                },
                                enabled = newTrigger.isNotBlank(),
                            ) { Text("Hinzufügen") }
                        }
                    }
                }
            }
        }
    }
}

/** Katalog-Namen und gespeicherte Namen können sich in Groß/klein unterscheiden. */
private fun levelOf(levels: Map<String, Int>, trigger: String) =
    levels.entries.firstOrNull { it.key.equals(trigger, ignoreCase = true) }?.value ?: 0

private fun setLevel(levels: MutableMap<String, Int>, trigger: String, value: Int) {
    levels.keys.filter { it.equals(trigger, ignoreCase = true) && it != trigger }.forEach(levels::remove)
    levels[trigger] = value
}

@Composable
private fun GroupHeader(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun LevelRow(trigger: String, level: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            trigger,
            style = MaterialTheme.typography.bodyMedium,
            color = if (level > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp),
        )
        Slider(
            value = level.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..MAX_TRIGGER_LEVEL.toFloat(),
            steps = MAX_TRIGGER_LEVEL - 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (level > 0) level.toString() else "–",
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
    }
}
