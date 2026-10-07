package com.airwhispers.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.airwhispers.R
import com.airwhispers.BuildConfig
import com.airwhispers.data.model.EmojiMode
import com.airwhispers.data.model.SpeechOutput
import com.airwhispers.ui.AppViewModel
import com.airwhispers.ui.components.InfoBanner
import com.airwhispers.ui.components.SectionCard
import com.airwhispers.ui.components.SettingRow
import com.airwhispers.ui.components.SwitchRow
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val speech by viewModel.speech.collectAsState()
    val session by viewModel.session.collectAsState()
    val relay by viewModel.relayState.collectAsState()
    // Collected, not read as `.value`: a StateFlow read inside composition would not
    // recompose when the server address changes.
    val backendUrl by viewModel.backendUrl.collectAsState()

    var choice by remember { mutableStateOf<Choice?>(null) }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = stringResource(R.string.settings_account)) {
            SettingRow(
                title = session.account?.displayName?.ifBlank { "Signed in" } ?: "Signed in",
                subtitle = session.account?.email?.ifBlank { backendUrl },
            )
            SettingRow(
                title = stringResource(R.string.settings_server),
                subtitle = backendUrl.ifBlank { stringResource(R.string.settings_server_not_configured) },
            )
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { viewModel.signOut() }) { Text(stringResource(R.string.settings_sign_out)) }
            }
        }

        SectionCard(title = stringResource(R.string.settings_tts)) {
            SettingRow(
                title = stringResource(R.string.settings_tts_language),
                subtitle = speech.languageTag,
                trailing = { Text("›") },
                onClick = {
                    choice = Choice.Language(
                        current = speech.languageTag,
                        options = listOf("en-IN", "en-US", "en-GB", "hi-IN", "en-AU", "en-SG"),
                    )
                },
            )
            LabelledSlider(
                title = stringResource(R.string.settings_tts_speed),
                value = speech.speechRate,
                range = 0.5f..2.0f,
                display = String.format(Locale.getDefault(), "%.2f×", speech.speechRate),
                onChange = { value -> viewModel.updateSpeech { it.copy(speechRate = value) } },
            )
            LabelledSlider(
                title = stringResource(R.string.settings_tts_pitch),
                value = speech.pitch,
                range = 0.5f..2.0f,
                display = String.format(Locale.getDefault(), "%.2f", speech.pitch),
                onChange = { value -> viewModel.updateSpeech { it.copy(pitch = value) } },
            )
            LabelledSlider(
                title = stringResource(R.string.settings_tts_pause_between),
                value = speech.pauseBetweenMessagesMs / 1000f,
                range = 0f..4f,
                display = "${(speech.pauseBetweenMessagesMs / 100f).roundToInt() / 10f}s",
                onChange = { value ->
                    viewModel.updateSpeech { it.copy(pauseBetweenMessagesMs = (value * 1000).toLong()) }
                },
            )
            SettingRow(
                title = stringResource(R.string.settings_tts_emoji),
                subtitle = AppViewModel.EMOJI_LABEL[speech.emojiMode] ?: speech.emojiMode.name,
                trailing = { Text("›") },
                onClick = { choice = Choice.Emoji(speech.emojiMode) },
            )
            SettingRow(
                title = stringResource(R.string.settings_tts_output),
                subtitle = AppViewModel.OUTPUT_LABEL[speech.output] ?: speech.output.name,
                trailing = { Text("›") },
                onClick = { choice = Choice.Output(speech.output) },
            )
            SettingRow(
                title = stringResource(R.string.call_assist_whisper_mode),
                subtitle = stringResource(R.string.call_assist_whisper_mode_summary),
                trailing = {
                    androidx.compose.material3.Switch(
                        checked = speech.whisperMode,
                        onCheckedChange = { value -> viewModel.updateSpeech { it.copy(whisperMode = value) } },
                    )
                },
            )
            Row(Modifier.padding(horizontal = 12.dp)) {
                Button(onClick = { viewModel.testSpeak() }) { Text(stringResource(R.string.settings_tts_test)) }
            }
        }

        SectionCard(title = stringResource(R.string.settings_notifications_reliability)) {
            SettingRow(
                title = stringResource(R.string.settings_notification_settings),
                subtitle = stringResource(R.string.settings_notification_settings_summary),
                trailing = { Text("›") },
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }
                },
            )
            SettingRow(
                title = stringResource(R.string.settings_allow_detection),
                subtitle = stringResource(R.string.settings_allow_detection_summary),
                trailing = { Text("›") },
                onClick = { phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SettingRow(
                    title = stringResource(R.string.settings_battery_ignore),
                    subtitle = stringResource(R.string.settings_battery_ignore_summary),
                    trailing = { Text("›") },
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }
                    },
                )
            }
            SettingRow(
                title = stringResource(R.string.settings_realtime),
                subtitle = relay.name.lowercase().replace('_', ' '),
            )
        }

        SectionCard(title = stringResource(R.string.settings_privacy)) {
            InfoBanner(
                "AirWhispers only handles its own messages, its own contacts and its own speech. " +
                    "It never records calls, never opens the microphone, never reads WhatsApp/Telegram/Signal " +
                    "content, and never touches another app's call audio.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            SettingRow(
                title = stringResource(R.string.settings_device_id),
                subtitle = viewModel.deviceId,
            )
            SettingRow(
                title = stringResource(R.string.settings_engine_title),
                subtitle = stringResource(R.string.settings_engine_summary),
            )
        }

        SectionCard(title = stringResource(R.string.settings_about)) {
            SettingRow(
                title = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                subtitle = stringResource(R.string.settings_about_build, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR),
            )
            SettingRow(
                title = stringResource(R.string.settings_docs_title),
                subtitle = stringResource(R.string.settings_docs_summary),
            )
        }
    }

    choice?.let { selection ->
        ChoiceDialog(
            choice = selection,
            onDismiss = { choice = null },
            onSelectLanguage = { tag ->
                viewModel.updateSpeech { it.copy(languageTag = tag) }
                choice = null
            },
            onSelectEmoji = { mode ->
                viewModel.updateSpeech { it.copy(emojiMode = mode) }
                choice = null
            },
            onSelectOutput = { output ->
                viewModel.updateSpeech { it.copy(output = output) }
                choice = null
            },
        )
    }
}

private sealed interface Choice {
    data class Language(val current: String, val options: List<String>) : Choice
    data class Emoji(val current: EmojiMode) : Choice
    data class Output(val current: SpeechOutput) : Choice
}

@Composable
private fun ChoiceDialog(
    choice: Choice,
    onDismiss: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectEmoji: (EmojiMode) -> Unit,
    onSelectOutput: (SpeechOutput) -> Unit,
) {
    val (title, options, select) = when (choice) {
        is Choice.Language -> Triple(
            "Language",
            choice.options,
            { value: String -> onSelectLanguage(value) },
        )
        is Choice.Emoji -> Triple(
            "Emoji speech",
            EmojiMode.entries.map { it.name },
            { value: String -> onSelectEmoji(EmojiMode.valueOf(value)) },
        )
        is Choice.Output -> Triple(
            "Output",
            SpeechOutput.entries.map { it.name },
            { value: String -> onSelectOutput(SpeechOutput.valueOf(value)) },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    val label = when (choice) {
                        is Choice.Emoji -> AppViewModel.EMOJI_LABEL[EmojiMode.valueOf(option)] ?: option
                        is Choice.Output -> AppViewModel.OUTPUT_LABEL[SpeechOutput.valueOf(option)] ?: option
                        else -> option
                    }
                    val selected = when (choice) {
                        is Choice.Language -> choice.current == option
                        is Choice.Emoji -> choice.current.name == option
                        is Choice.Output -> choice.current.name == option
                    }
                    TextButton(
                        onClick = { select(option) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            (if (selected) "● " else "○ ") + label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (choice is Choice.Output) {
                    Spacer(Modifier.height(6.dp))
                    InfoBanner(
                        "Android routes spoken messages through the normal media output. When earphones are " +
                            "connected they are used automatically; AirWhispers will not force the loudspeaker " +
                            "because that can change the routing of the call you are on.",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun LabelledSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(display, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}