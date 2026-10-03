package de.axelcypher.asmr.ui.login

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(viewModel: LoginViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.openInBrowser.collect { url ->
            CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("ASMR Player", style = MaterialTheme.typography.headlineMedium)

        val config = state.authConfig
        if (config == null) {
            ServerStep(state, viewModel)
        } else {
            ServerRow(state.serverUrl, onChange = viewModel::changeServer)
            config.sso?.let { sso ->
                Button(onClick = viewModel::startSso, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text("Mit ${sso.providerName} anmelden")
                }
                if (config.passwordLogin) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            if (config.passwordLogin) PasswordStep(state, viewModel, primary = config.sso == null)
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.isLoading) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun ServerStep(state: LoginUiState, viewModel: LoginViewModel) {
    Text(
        "Mit deinem ASMR-Server verbinden",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = viewModel::onServerUrlChange,
        label = { Text("Server-URL") },
        placeholder = { Text("https://asmr.example.de") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { viewModel.connect() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = viewModel::connect, enabled = state.canConnect, modifier = Modifier.fillMaxWidth()) {
        Text("Verbinden")
    }
}

@Composable
private fun PasswordStep(state: LoginUiState, viewModel: LoginViewModel, primary: Boolean) {
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = { Text("Benutzername") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text("Passwort") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { viewModel.login() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (primary) {
        Button(onClick = viewModel::login, enabled = state.canLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Anmelden")
        }
    } else {
        OutlinedButton(onClick = viewModel::login, enabled = state.canLogin, modifier = Modifier.fillMaxWidth()) {
            Text("Mit Passwort anmelden")
        }
    }
}

@Composable
private fun ServerRow(serverUrl: String, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            serverUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text("Ändern") }
    }
}
