package de.axelcypher.asmr.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.axelcypher.asmr.BuildConfig
import de.axelcypher.asmr.api.UserDto
import de.axelcypher.asmr.ui.AppIcons
import kotlinx.coroutines.launch

private const val MIN_PASSWORD_LENGTH = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profil") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(AppIcons.ArrowBack, contentDescription = "Zurück") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val me = state.me
            if (me != null) {
                Text(me.displayName ?: me.username, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "@${me.username}" + if (me.isAdmin) " · Admin" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordCard(hasPassword = me.hasPassword, busy = state.isBusy, onChange = viewModel::changePassword)
                if (me.isAdmin) AdminCard(me, state.users, state.isBusy, viewModel)
            }
            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) { Text("Abmelden") }
            DiagnosticsCard()
            Text(
                "App-Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bildfehler und ein Test, der ein Cover am Cache vorbei über denselben Bildlader lädt. */
@Composable
private fun DiagnosticsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as de.axelcypher.asmr.AsmrApp).container
    val errors by container.imageErrors.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnose", style = MaterialTheme.typography.titleMedium)
            Text("Letzte Bildfehler", style = MaterialTheme.typography.titleSmall)
            if (errors.isEmpty()) Text("Keine", style = MaterialTheme.typography.bodySmall)
            errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = {
                result = "Läuft …"
                scope.launch {
                    result = runCatching {
                        val session = container.sessionStore.current() ?: return@runCatching "Nicht angemeldet"
                        val home = container.asmrClient.home()
                        val item = (home.favorites + home.ambient).firstOrNull { it.hasCover }
                            ?: return@runCatching "Kein Track mit Cover in der Übersicht"
                        val url = de.axelcypher.asmr.data.api.AsmrClient.coverUrl(session.serverUrl, item.id)
                        val request = coil3.request.ImageRequest.Builder(context)
                            .data(url)
                            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                            .diskCachePolicy(coil3.request.CachePolicy.DISABLED)
                            .build()
                        when (val r = coil3.SingletonImageLoader.get(context).execute(request)) {
                            is coil3.request.SuccessResult -> "OK: ${r.image.width}×${r.image.height} von $url"
                            is coil3.request.ErrorResult -> "Fehler bei $url: ${r.throwable}"
                        }
                    }.getOrElse { "Fehler: $it" }
                }
            }) { Text("Bild testen") }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PasswordCard(hasPassword: Boolean, busy: Boolean, onChange: (String?, String, () -> Unit) -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val mismatch = repeat.isNotEmpty() && new != repeat
    val tooShort = new.isNotEmpty() && new.length < MIN_PASSWORD_LENGTH
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (hasPassword) "Passwort ändern" else "Passwort festlegen", style = MaterialTheme.typography.titleMedium)
            if (!hasPassword) {
                Text(
                    "Dein Konto meldet sich per SSO an. Mit einem Passwort geht es auch ohne.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (hasPassword) PasswordField("Aktuelles Passwort", current) { current = it }
            PasswordField("Neues Passwort", new, error = if (tooShort) "Mindestens $MIN_PASSWORD_LENGTH Zeichen" else null) { new = it }
            PasswordField("Wiederholen", repeat, error = if (mismatch) "Stimmt nicht überein" else null) { repeat = it }
            Button(
                onClick = {
                    onChange(current.takeIf { hasPassword }, new) {
                        current = ""
                        new = ""
                        repeat = ""
                    }
                },
                enabled = !busy && new.length >= MIN_PASSWORD_LENGTH && new == repeat && (!hasPassword || current.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
        }
    }
}

@Composable
private fun AdminCard(me: UserDto, users: List<UserDto>, busy: Boolean, viewModel: ProfileViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<UserDto?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Verwaltung", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = viewModel::scanLibrary, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Medienordner jetzt scannen")
            }
            HorizontalDivider()
            Text("Konten", style = MaterialTheme.typography.titleSmall)
            users.forEach { user ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(user.username)
                        Text(
                            listOfNotNull(
                                "Admin".takeIf { user.isAdmin },
                                "nur SSO".takeIf { !user.hasPassword },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (user.id != me.id) TextButton(onClick = { toDelete = user }) { Text("Löschen") }
                }
            }
            OutlinedButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) { Text("Konto anlegen") }
        }
    }

    if (showCreate) CreateUserDialog(onCreate = { name, pw, admin ->
        viewModel.createUser(name, pw, admin) { showCreate = false }
    }, onDismiss = { showCreate = false })

    toDelete?.let { user ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("${user.username} löschen?") },
            text = { Text("Das Konto und seine Favoriten werden entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUser(user)
                    toDelete = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun CreateUserDialog(onCreate: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konto anlegen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Benutzername") }, singleLine = true)
                PasswordField("Passwort (min. $MIN_PASSWORD_LENGTH Zeichen)", password) { password = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = admin, onCheckedChange = { admin = it })
                    Text("Admin")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, password, admin) },
                enabled = name.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH,
            ) { Text("Anlegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun PasswordField(label: String, value: String, error: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
