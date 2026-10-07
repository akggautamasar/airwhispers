package com.airwhispers.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.airwhispers.R
import com.airwhispers.data.model.CallDetectionSource
import com.airwhispers.data.model.CallState
import com.airwhispers.service.CallAssistController
import com.airwhispers.ui.AppViewModel
import com.airwhispers.ui.components.InfoBanner
import com.airwhispers.ui.components.SectionCard
import com.airwhispers.ui.components.SettingRow
import com.airwhispers.ui.components.StatusPill
import com.airwhispers.ui.components.SwitchRow

/**
 * The Call Assist cockpit: one obvious switch, a live view of what is being said,
 * and an honest description of what this device can detect.
 */
@Composable
fun CallAssistScreen(viewModel: AppViewModel, startCallAssist: () -> Unit) {
    val context = LocalContext.current
    val callAssist by viewModel.callAssist.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val callStatus by viewModel.callStatus.collectAsState()
    val capability by viewModel.capability.collectAsState()
    var manualCall by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = if (callAssist.enabled) "Active" else "Off",
                        active = callAssist.enabled,
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        callStatusLabel(callStatus.state, callStatus.source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (callAssist.enabled) {
                        "AirWhispers is listening for messages. Anything allowed by your rules will be spoken through ${viewModel.audioRoute.lowercase()}."
                    } else {
                        "Start Call Assist right before or during a call. Nothing is spoken until then."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (callAssist.enabled) {
                        OutlinedButton(
                            onClick = { CallAssistController.stop(context) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.call_assist_stop_speech)) }
                    } else {
                        Button(
                            onClick = {
                                startCallAssist()
                                viewModel.setCallAssistEnabled(true)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.call_assist_start)) }
                    }
                    OutlinedButton(
                        onClick = {
                            manualCall = !manualCall
                            viewModel.setManualCallOverride(manualCall)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (manualCall) "End mock call" else "I'm in a call") }
                }
            }
        }

        SectionCard(title = stringResource(R.string.call_assist_queue_title)) {
            SettingRow(
                title = queue.current?.let { "Speaking: ${it.senderName}" } ?: "Nothing playing",
                subtitle = queue.current?.body?.take(90) ?: "Messages wait here so they never overlap.",
            )
            SettingRow(
                title = stringResource(R.string.call_assist_waiting_title, queue.depth),
                subtitle = stringResource(R.string.call_assist_spoken_count, queue.spokenCount),
            )
            queue.lastError?.let { error ->
                SettingRow(title = stringResource(R.string.call_assist_problem_title), subtitle = error)
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = { if (queue.paused) CallAssistController.resume(context) else CallAssistController.pause(context) },
                ) { Text(if (queue.paused) "Resume" else "Pause") }
                TextButton(onClick = { CallAssistController.skip(context) }) { Text(stringResource(R.string.call_assist_skip)) }
                TextButton(onClick = { CallAssistController.clearQueue(context) }) { Text(stringResource(R.string.call_assist_clear_queue)) }
            }
        }

        SectionCard(title = stringResource(R.string.call_assist_rules)) {
            SwitchRow(
                title = stringResource(R.string.call_assist_read_aloud),
                subtitle = stringResource(R.string.call_assist_read_aloud_summary),
                checked = callAssist.speakMessages,
                onCheckedChange = { value ->
                    viewModel.updateCallAssist { it.copy(speakMessages = value) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.call_assist_only_in_calls),
                subtitle = stringResource(R.string.call_assist_only_in_calls_summary),
                checked = callAssist.onlyDuringCalls,
                onCheckedChange = { value ->
                    viewModel.updateCallAssist { it.copy(onlyDuringCalls = value) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.call_assist_trusted_only),
                subtitle = stringResource(R.string.call_assist_trusted_only_summary),
                checked = callAssist.trustedContactsOnly,
                onCheckedChange = { value ->
                    viewModel.updateCallAssist { it.copy(trustedContactsOnly = value) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.call_assist_prefer_bluetooth),
                subtitle = stringResource(R.string.call_assist_prefer_bluetooth_summary),
                checked = callAssist.preferBluetooth,
                onCheckedChange = { value ->
                    viewModel.updateCallAssist { it.copy(preferBluetooth = value) }
                },
            )
        }

        SectionCard(title = stringResource(R.string.call_assist_capability_title)) {
            SettingRow(
                title = if (capability.automaticDetectionAvailable) "Automatic detection available" else "Manual mode only",
                subtitle = if (capability.automaticDetectionAvailable) {
                    "Phone calls and communication sessions are detected automatically."
                } else {
                    "Start Call Assist yourself before or during a call."
                },
            )
            capability.notes.forEach { note ->
                InfoBanner(note, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        SectionCard(title = stringResource(R.string.call_assist_audio)) {
            SettingRow(title = stringResource(R.string.call_assist_output_now), subtitle = viewModel.audioRoute)
            InfoBanner(
                "AirWhispers speaks to *you* through this phone's audio output. It never joins, records or changes the audio of your WhatsApp, Meet, Discord or phone call — and it never disconnects your headphones.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

private fun callStatusLabel(state: CallState, source: CallDetectionSource): String = when (state) {
    CallState.IN_CALL -> when (source) {
        CallDetectionSource.TELEPHONY -> "Phone call in progress"
        CallDetectionSource.MANUAL -> "Marked as in a call"
        else -> "Call in progress"
    }
    CallState.IN_COMMUNICATION -> if (source == CallDetectionSource.MICROPHONE_IN_USE) {
        "Voice chat likely (microphone in use)"
    } else {
        "Communication session detected"
    }
    CallState.RINGING -> "Incoming call"
    CallState.IDLE -> "No call detected"
}