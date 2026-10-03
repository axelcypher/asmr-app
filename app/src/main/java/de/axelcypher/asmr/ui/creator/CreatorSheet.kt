package de.axelcypher.asmr.ui.creator

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.axelcypher.asmr.api.CreatorDto
import de.axelcypher.asmr.data.api.AsmrClient
import de.axelcypher.asmr.ui.library.serverMessage
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profil eines Creators: Bild und Links zu YouTube, Patreon, Fansly & Co. Admins pflegen Links und
 * Profilbild (aus der Galerie oder vom YouTube-Kanal); das Bild dient als Cover-Fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorSheet(
    name: String,
    client: AsmrClient,
    serverUrl: String,
    isAdmin: Boolean,
    onImagesChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var creator by remember { mutableStateOf<CreatorDto?>(null) }
    var editing by remember { mutableStateOf(false) }
    var linksText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Hängt an der Bild-URL, damit Coil nach einem Wechsel nicht das alte Bild aus dem Cache zeigt.
    var imageVersion by remember { mutableIntStateOf(0) }

    fun run(block: suspend () -> CreatorDto) {
        busy = true
        message = null
        scope.launch {
            try {
                creator = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                message = e.serverMessage()
            } catch (e: Exception) {
                message = e.message ?: "Fehlgeschlagen"
            } finally {
                busy = false
            }
        }
    }

    fun imageChanged() {
        imageVersion++
        onImagesChanged()
    }

    LaunchedEffect(name) { run { client.creator(name) } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) run {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            client.uploadAvatar(name, bytes).also { imageChanged() }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (creator?.hasAvatar == true) {
                    AsyncImage(
                        model = "${AsmrClient.avatarUrl(serverUrl, name)}?v=$imageVersion",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp),
                    )
                }
            }
            Text(name, style = MaterialTheme.typography.headlineSmall)

            val links = creator?.links.orEmpty()
            if (links.isEmpty() && !editing) {
                Text("Noch keine Links", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                links.forEach { link ->
                    AssistChip(onClick = { uriHandler.openUri(link.url) }, label = { Text(link.label) })
                }
            }

            if (isAdmin) {
                if (editing) {
                    OutlinedTextField(
                        linksText,
                        { linksText = it },
                        label = { Text("Links, einer pro Zeile") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = false }) { Text("Abbrechen") }
                        TextButton(
                            onClick = {
                                editing = false
                                run { client.setCreatorLinks(name, linksText.lines()) }
                            },
                            enabled = !busy,
                        ) { Text("Speichern") }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        OutlinedButton(onClick = {
                            linksText = links.joinToString("\n") { it.url }
                            editing = true
                        }) { Text("Links bearbeiten") }
                        OutlinedButton(
                            onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = !busy,
                        ) { Text("Bild wählen") }
                        OutlinedButton(
                            onClick = { run { client.fetchAvatar(name).also { imageChanged() } } },
                            enabled = !busy && links.any { it.label == "YouTube" },
                        ) { Text("Bild von YouTube") }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) Text("Bitte warten …", style = MaterialTheme.typography.bodySmall)
        }
    }
}
