package com.airwhispers.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airwhispers.data.model.Conversation
import com.airwhispers.ui.AppViewModel
import com.airwhispers.ui.components.Avatar
import com.airwhispers.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String, val emoji: String) {
    CHATS("Chats", "💬"),
    ASSIST("Call Assist", "🔊"),
    SETTINGS("Settings", "⚙️"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScaffold(
    viewModel: AppViewModel,
    startCallAssist: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.CHATS) }
    val conversations by viewModel.conversations.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val callAssist by viewModel.callAssist.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (tab) {
                                Tab.CHATS -> "AirWhispers"
                                Tab.ASSIST -> "Call Assist"
                                Tab.SETTINGS -> "Settings"
                            },
                        )
                        if (tab == Tab.CHATS) {
                            Text(
                                if (callAssist.enabled) "Listening while you are on a call" else "Not listening",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (callAssist.enabled && (queue.current != null || queue.pending.isNotEmpty())) {
                        TextButton(onClick = { viewModel.stopSpeech() }) { Text("Stop") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.emoji) },
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.CHATS -> ChatList(
                    viewModel = viewModel,
                    conversations = conversations,
                    onOpenChat = onOpenChat,
                )
                Tab.ASSIST -> CallAssistScreen(viewModel = viewModel, startCallAssist = startCallAssist)
                Tab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun ChatList(
    viewModel: AppViewModel,
    conversations: List<Conversation>,
    onOpenChat: (String) -> Unit,
) {
    var showNewChat by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { MyCodeCard(viewModel) }
            if (conversations.isEmpty()) {
                item {
                    EmptyState(
                        title = "No chats yet",
                        body = "Share the code above, or add someone else's code with the ✏️ button. " +
                            "Anything they send can be whispered to you while you are on a call.",
                    )
                }
            } else {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(conversation) { onOpenChat(conversation.id) }
                }
            }
        }
        FloatingActionButton(
            onClick = { showNewChat = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            shape = CircleShape,
        ) {
            Text("✏️")
        }
    }

    if (showNewChat) {
        NewChatDialog(
            onDismiss = { showNewChat = false },
            onStart = { code, onError ->
                viewModel.openChatWithCode(code) { conversationId, error ->
                    if (error != null) {
                        onError(error.message)
                    } else {
                        showNewChat = false
                        conversationId?.let(onOpenChat)
                    }
                }
            },
        )
    }
}

/** The one thing a stranger needs in order to reach this phone. */
@Composable
private fun MyCodeCard(viewModel: AppViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val code = viewModel.myCode

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                "YOUR CODE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                viewModel.myPrettyCode,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This code is this phone. Anyone you give it to can start a chat — nothing else is needed, " +
                    "and only you decide whose messages may be spoken aloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (code != null) clipboard.setText(AnnotatedString(code))
                    },
                    enabled = code != null,
                ) { Text("Copy") }
                TextButton(
                    onClick = {
                        if (code == null) return@TextButton
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Whisper to me on AirWhispers — my code is ${viewModel.myPrettyCode} " +
                                    "(server: ${viewModel.backendUrl.value})",
                            )
                        }
                        context.startActivity(Intent.createChooser(intent, "Share my code"))
                    },
                    enabled = code != null,
                ) { Text("Share") }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(conversation.peerDisplayName)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.peerDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.peerOnline) {
                        Spacer(Modifier.width(6.dp))
                        Text("🟢", modifier = Modifier.size(12.dp))
                    }
                    if (conversation.youAllowSpeak) {
                        Spacer(Modifier.width(6.dp))
                        Text("🔊", modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    conversation.lastMessageText ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatTimestamp(conversation.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Badge { Text(conversation.unreadCount.coerceAtMost(99).toString()) }
                }
            }
        }
    }
}

@Composable
private fun NewChatDialog(
    onDismiss: () -> Unit,
    onStart: (String, (String) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            Column {
                Text(
                    "Type the code the other person sees on their AirWhispers home screen — " +
                        "six characters, like k7m2pq.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    label = { Text("Their code") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(code) { message -> error = message } },
                enabled = code.count { it.isLetterOrDigit() } >= 6,
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatTimestamp(millis: Long?): String {
    if (millis == null || millis <= 0L) return ""
    val now = System.currentTimeMillis()
    val format = if (now - millis < 24 * 60 * 60 * 1000) "HH:mm" else "dd MMM"
    return SimpleDateFormat(format, Locale.getDefault()).format(Date(millis))
}
