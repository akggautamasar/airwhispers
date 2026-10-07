package com.airwhispers.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airwhispers.R
import com.airwhispers.core.AppError
import com.airwhispers.ui.AppViewModel
import com.airwhispers.ui.components.InfoBanner
import com.airwhispers.ui.components.WarningBanner
import kotlinx.coroutines.launch

/**
 * Server setup + sign in/up, in one calm flow.
 *
 * The server field comes first on purpose: AirWhispers is designed to be
 * self-hostable, and until a server is configured there is nothing to sign in to.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel) {
    val configuredUrl by viewModel.backendUrl.collectAsState()
    var serverUrl by remember(configuredUrl) { mutableStateOf(configuredUrl) }
    var step by remember { mutableStateOf(if (configuredUrl.isBlank()) Step.SERVER else Step.ACCOUNT) }
    var mode by remember { mutableStateOf(Mode.LOGIN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

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
        Spacer(Modifier.height(28.dp))

        if (step == Step.SERVER) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("AirWhispers server") },
                placeholder = { Text("https://chat.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask whoever runs your AirWhispers server for this address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (serverUrl.startsWith("http://")) {
                Spacer(Modifier.height(12.dp))
                WarningBanner(
                    "This address is not encrypted. Only use http:// on a network you trust (for example your own Wi-Fi).",
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    busy = true
                    errorText = null
                    viewModel.configureBackend(serverUrl)
                    viewModel.checkServer { reachable, message ->
                        busy = false
                        if (reachable) {
                            statusText = "Server reachable"
                            step = Step.ACCOUNT
                        } else {
                            errorText = message ?: "Could not reach that server."
                        }
                    }
                },
                enabled = serverUrl.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Continue")
                }
            }
            TextButton(onClick = { step = Step.ACCOUNT }) { Text("Skip check and continue") }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { mode = Mode.LOGIN; errorText = null }) {
                    Text(
                        "Sign in",
                        color = if (mode == Mode.LOGIN) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { mode = Mode.REGISTER; errorText = null }) {
                    Text(
                        "Create account",
                        color = if (mode == Mode.REGISTER) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (mode == Mode.REGISTER) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
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
                    val onResult: (AppError?) -> Unit = { error ->
                        busy = false
                        errorText = error?.message
                    }
                    if (mode == Mode.LOGIN) {
                        viewModel.signIn(email, password, onResult)
                    } else {
                        viewModel.register(email, password, displayName.ifBlank { email.substringBefore('@') }, onResult)
                    }
                },
                enabled = email.isNotBlank() && password.length >= 8 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (mode == Mode.LOGIN) "Sign in" else "Create account")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (password.isEmpty() || password.length >= 8) {
                    "Server: $configuredUrl"
                } else {
                    "Passwords need at least 8 characters."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { step = Step.SERVER }) { Text("Change server") }
        }
    }
}

private enum class Step { SERVER, ACCOUNT }

private enum class Mode { LOGIN, REGISTER }
