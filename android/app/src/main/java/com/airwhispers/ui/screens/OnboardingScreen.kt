package com.airwhispers.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airwhispers.R
import com.airwhispers.core.AppError
import com.airwhispers.ui.AppViewModel
import com.airwhispers.ui.components.InfoBanner
import com.airwhispers.ui.components.SectionCard
import com.airwhispers.ui.components.WarningBanner

/**
 * Two steps and you are done — forever.
 *
 * Step 1: which server relays your messages (the "what is a server?" card explains
 * it, because the honest answer is "a small program somebody runs for you, or you
 * run yourself"). Step 2: your name. There is no account, no email, no password
 * and no verification code: the server answers with a six-character code, and that
 * code *is* your address from then on.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel) {
    val configuredUrl by viewModel.backendUrl.collectAsState()
    var serverUrl by remember(configuredUrl) { mutableStateOf(configuredUrl) }
    var step by remember { mutableStateOf(if (configuredUrl.isBlank()) Step.SERVER else Step.NAME) }
    var displayName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("AirWhispers", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Messages you can hear — even while you are on a call in another app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        if (step == Step.SERVER) {
            SectionCard(title = "What is a server?") {
                Text(
                    "There is no company in the middle of AirWhispers. A “server” is a small program " +
                        "that relays messages between phones: you can run it on a laptop or a cheap " +
                        "machine, or use the address somebody gave you. It never sees more than what " +
                        "you and your friends type.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
                Text(
                    "Start your own with one command:  npm run dev  (from the backend folder).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.10:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask whoever invited you for this address — or use your own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (serverUrl.startsWith("http://")) {
                Spacer(Modifier.height(12.dp))
                WarningBanner(
                    "This address is not encrypted. Use http:// only on a network you trust (for example your own Wi-Fi).",
                )
            }
            errorText?.let {
                Spacer(Modifier.height(12.dp))
                WarningBanner(it)
            }
            statusText?.let {
                Spacer(Modifier.height(12.dp))
                InfoBanner(it)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    busy = true
                    errorText = null
                    statusText = null
                    viewModel.configureBackend(serverUrl)
                    viewModel.checkServer { info, error ->
                        busy = false
                        if (info != null) {
                            statusText = "Server ready · version ${info.version} · no accounts, just codes."
                            step = Step.NAME
                        } else {
                            errorText = error?.message ?: "Could not reach that server."
                        }
                    }
                },
                enabled = serverUrl.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Check server")
                }
            }
            TextButton(onClick = { step = Step.NAME }) { Text("Skip the check and continue") }
        } else {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Your name") },
                placeholder = { Text("Anu") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Only the people you give your code to will see this name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            errorText?.let {
                Spacer(Modifier.height(12.dp))
                WarningBanner(it)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    busy = true
                    errorText = null
                    val onResult: (AppError?) -> Unit = { error ->
                        busy = false
                        errorText = error?.message
                    }
                    viewModel.completeSetup(displayName.ifBlank { "This phone" }, onResult)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Get my code")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Server: ${configuredUrl.ifBlank { "not set" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { step = Step.SERVER; errorText = null }) { Text("Change server") }
        }
    }
}

private enum class Step { SERVER, NAME }
