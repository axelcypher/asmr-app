package de.axelcypher.asmr.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.axelcypher.asmr.api.ImportJobDto
import de.axelcypher.asmr.api.ImportStatus

/** Geteilte Texte enthalten oft mehr als die URL ("Schau dir das an: https://…"). */
fun extractUrl(text: String): String = Regex("https?://\\S+").find(text)?.value ?: text.trim()

@Composable
fun ImportDialog(initialUrl: String, onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember(initialUrl) { mutableStateOf(extractUrl(initialUrl)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importieren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Der Server lädt das Audio per yt-dlp (YouTube und viele andere Seiten).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(url) }, enabled = url.isNotBlank()) { Text("Importieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportsSheet(imports: List<ImportJobDto>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text("Importe", style = MaterialTheme.typography.titleLarge)
            if (imports.isEmpty()) {
                Text("Noch keine Importe", modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.padding(vertical = 8.dp)) {
                items(imports, key = { it.id }) { job ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(job.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (job.status) {
                                ImportStatus.QUEUED -> "Wartet"
                                ImportStatus.RUNNING -> "Lädt herunter …"
                                ImportStatus.DONE -> "Fertig"
                                ImportStatus.FAILED -> "Fehler: ${job.error.orEmpty()}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (job.status == ImportStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
