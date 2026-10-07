package com.airwhispers.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.airwhispers.BuildConfig
import com.airwhispers.data.model.EmojiMode
import com.airwhispers.ui.AppViewModel
import com.airwhispers.ui.components.InfoBanner
import com.airwhispers.ui.components.SectionCard
import com.airwhispers.ui.components.SettingRow
import com.airwhispers.ui.components.SwitchRow

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val speech by viewModel.speech.collectAsState()
    val callAssist by viewModel.callAssist.collectAsState()
    val relay by viewModel.relayState.collectAsState()
    var showNewCodeDialog by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "This device") {
            SettingRow(
                title = "Your code",
                subtitle = "Give it to someone so they can whisper to you.",
                trailing = { Text(viewModel.myPrettyCode, style = MaterialTheme.typography.titleMedium) },
            )
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = {
                    viewModel.myCode?.let { clipboard.setText(AnnotatedString(it)) }
                }) { Text("Copy code") }
                TextButton(onClick = { showNewCodeDialog = true }) { Text("Get a new code") }
            }
            SettingRow(
                title = "Server",
                subtitle = viewModel.backendUrl.value.ifBlank { "Not configured" },
            )
            SettingRow(
                title = "Realtime connection",
                subtitle = relay.name.lowercase().replace('_', ' '),
            )
        }

        SectionCard(title = "Speech") {
            SettingRow(
                title = "Language",
                subtitle = speech.languageTag,
                trailing = { Text("›") },
            )
            SettingRow(
                title = "Speed",
                subtitle = "${"%.2f".format(speech.speechRate)}×",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = {
                            viewModel.updateSpeech { it.copy(speechRate = (it.speechRate - 0.1f).coerceAtLeast(0.3f)) }
                        }) { Text("−") }
                        TextButton(onClick = {
                            viewModel.updateSpeech { it.copy(speechRate = (it.speechRate + 0.1f).coerceAtMost(2f)) }
                        }) { Text("+") }
                    }
                },
            )
            SettingRow(
                title = "Pitch",
                subtitle = "${"%.2f".format(speech.pitch)}×",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = {
                            viewModel.updateSpeech { it.copy(pitch = (it.pitch - 0.1f).coerceAtLeast(0.5f)) }
                        }) { Text("−") }
                        TextButton(onClick = {
                            viewModel.updateSpeech { it.copy(pitch = (it.pitch + 0.1f).coerceAtMost(2f)) }
                        }) { Text("+") }
                    }
                },
            )
            SettingRow(
                title = "Pause between messages",
                subtitle = "${speech.pauseBetweenMessagesMs} ms",
            )
            SettingRow(
                title = "Emoji speech",
                subtitle = AppViewModel.EMOJI_LABEL[speech.emojiMode] ?: speech.emojiMode.name,
                trailing = { Text("›") },
                onClick = { showEmojiPicker = true },
            )
            SettingRow(
                title = "Output",
                subtitle = AppViewModel.OUTPUT_LABEL[speech.output] ?: speech.output.name,
            )
            SwitchRow(
                title = "Whisper mode",
                subtitle = "Speak softly — meant for earbuds while you are on a call, not for the room.",
                checked = speech.whisperMode,
                onCheckedChange = { value -> viewModel.updateSpeech { it.copy(whisperMode = value) } },
            )
            Row(Modifier.padding(horizontal = 12.dp)) {
                Button(onClick = { viewModel.testSpeak() }) { Text("Test voice") }
            }
        }

        SectionCard(title = "Notifications & reliability") {
            SettingRow(
                title = "Notification settings",
                subtitle = "Choose how AirWhispers may alert you.",
                trailing = { Text("›") },
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(intent)
                },
            )
            InfoBanner(
                "AirWhispers never asks for the microphone and never touches your calls. " +
                    "Call Assist only observes that a call is happening, then speaks to you.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (callAssist.onlyDuringCalls && !callAssist.speakMessages) {
                InfoBanner(
                    "Call Assist is armed but silent: switch on “Speak incoming messages” in the Call Assist tab.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        SectionCard(title = "Privacy") {
            SettingRow(
                title = "Device id",
                subtitle = viewModel.deviceId,
            )
            SettingRow(
                title = "Your phone's own voice",
                subtitle = "Speech uses the engine on this device — message text is never sent to a cloud service.",
            )
            SettingRow(
                title = "No accounts",
                subtitle = "No email, no password, no phone number. The code above is the whole identity, and " +
                    "the secret behind it never leaves this phone.",
            )
        }

        SectionCard(title = "About") {
            SettingRow(
                title = "AirWhispers ${BuildConfig.VERSION_NAME}",
                subtitle = "Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.FLAVOR}",
            )
            SettingRow(
                title = "Messages you can hear",
                subtitle = "AirWhispers speaks incoming messages to you — during a call in any other app.",
            )
        }
    }

    if (showNewCodeDialog) {
        AlertDialog(
            onDismissRequest = { showNewCodeDialog = false },
            title = { Text("Get a new code?") },
            text = {
                Text(
                    "This device will forget the old code and ask the server for a new one. " +
                        "Chats you already have stay where they are, but people who only know the old " +
                        "code will no longer reach you.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewCodeDialog = false
                    viewModel.requestNewCode { }
                }) { Text("Get a new code") }
            },
            dismissButton = { TextButton(onClick = { showNewCodeDialog = false }) { Text("Cancel") } },
        )
    }

    if (showEmojiPicker) {
        AlertDialog(
            onDismissRequest = { showEmojiPicker = false },
            title = { Text("How should emojis be read?") },
            text = {
                Column {
                    EmojiMode.entries.forEach { mode ->
                        TextButton(onClick = {
                            viewModel.updateSpeech { it.copy(emojiMode = mode) }
                            showEmojiPicker = false
                        }) { Text(AppViewModel.EMOJI_LABEL[mode] ?: mode.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEmojiPicker = false }) { Text("Close") } },
        )
    }
}
