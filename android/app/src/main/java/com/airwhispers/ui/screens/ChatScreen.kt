package com.airwhispers.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.DeliveryState
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import com.airwhispers.ui.AppViewModel

/** How often this device tells the other side "still typing". */
private const val TYPING_PING_MS = 2_500L

/**
 * 1-to-1 chat, addressed by code.
 *
 * Send is the ordinary path; **Whisper now** asks the other phone's Call Assist to
 * speak the message the moment it is allowed to — the "important, listen now"
 * channel of the product. Whether it is actually spoken is decided *there*, by the
 * person holding that phone: the header switch shows their answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    conversationId: String,
    onBack: () -> Unit,
) {
    val conversations by viewModel.conversations.collectAsState()
    val allMessages by viewModel.messages.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val typing by viewModel.typing.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val messages = allMessages[conversationId].orEmpty()
    val peerTyping = typing?.conversationId == conversationId

    var draft by remember { mutableStateOf("") }
    var lastTypingPing by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()

    LaunchedEffect(conversationId) {
        viewModel.loadMessages(conversationId)
        viewModel.markRead(conversationId)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conversation?.peerDisplayName ?: "Chat")
                        Text(
                            subtitleFor(conversation, peerTyping),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } },
                actions = {
                    conversation?.let { c ->
                        TextButton(
                            onClick = { viewModel.setSpeakAllowed(c, !c.youAllowSpeak) },
                        ) {
                            Text(
                                if (c.youAllowSpeak) "🔊 whispers on" else "🔇 muted",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (queue.current?.conversationId == conversationId) {
                        Text(
                            "Whispering now…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        )
                    }
                    if (draft.isNotBlank() && conversation?.peerAllowsSpeak == false) {
                        Text(
                            "They have not allowed whispers from you yet — “Whisper now” will arrive as a normal message.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { value ->
                                draft = value
                                val now = System.currentTimeMillis()
                                if (value.isNotBlank() && now - lastTypingPing > TYPING_PING_MS) {
                                    lastTypingPing = now
                                    viewModel.notifyTyping(conversationId)
                                }
                            },
                            placeholder = { Text("Message") },
                            maxLines = 5,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.padding(4.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TextButton(
                                onClick = {
                                    viewModel.send(conversationId, draft, speakNow = true)
                                    draft = ""
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text("🔊 Whisper", style = MaterialTheme.typography.labelSmall) }
                            TextButton(
                                onClick = {
                                    viewModel.send(conversationId, draft, speakNow = false)
                                    draft = ""
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text("Send") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Say something. If they are on a call, it can be whispered to them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.clientMessageId }) { message ->
                        MessageBubble(
                            message = message,
                            onRetry = { viewModel.retry(message.clientMessageId) },
                        )
                    }
                }
            }
        }
    }
}

private fun subtitleFor(conversation: Conversation?, peerTyping: Boolean): String = when {
    peerTyping -> "typing…"
    conversation == null -> ""
    conversation.peerOnline -> "code ${prettyCode(conversation.peerCode)} · online"
    else -> "code ${prettyCode(conversation.peerCode)} · offline"
}

internal fun prettyCode(code: String): String =
    code.uppercase().replace(Regex("(.{3})(.{3})"), "$1 $2")

@Composable
private fun MessageBubble(message: Message, onRetry: () -> Unit) {
    val alignment = if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart
    val container = if (message.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = container,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.isMine) 18.dp else 4.dp,
                bottomEnd = if (message.isMine) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.priority == MessagePriority.SPEAK_NOW) {
                    Text(
                        "🔊 Whisper now",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildStatusLabel(message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message.state == MessageState.FAILED) {
                    TextButton(onClick = onRetry) { Text("Retry", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

private fun buildStatusLabel(message: Message): String {
    val time = formatTimestamp(message.createdAt)
    return when {
        !message.isMine -> listOfNotNull(
            time,
            if (message.spokenAt != null || message.spokenByRecipient) "whispered" else null,
        ).joinToString(" · ")
        message.state == MessageState.PENDING -> "$time · sending"
        message.state == MessageState.FAILED -> "$time · not sent"
        message.deliveryState == DeliveryState.READ -> "$time · read"
        message.deliveryState == DeliveryState.DELIVERED -> "$time · delivered"
        else -> "$time · sent"
    }
}
