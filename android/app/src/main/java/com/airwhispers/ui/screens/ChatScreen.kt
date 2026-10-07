package com.airwhispers.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airwhispers.data.model.DeliveryState
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import com.airwhispers.ui.AppViewModel

/**
 * 1-to-1 conversation.
 *
 * Send is the ordinary path; **Speak Now** marks a message so a recipient with
 * Call Assist hears it as soon as their queue allows — the "important, listen
 * now" channel of the product.
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
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val messages = allMessages[conversationId].orEmpty()

    var draft by remember { mutableStateOf("") }
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
                        Text(conversation?.peerDisplayName ?: "Conversation")
                        Text(
                            if (conversation?.peerTrusted == true) {
                                "Trusted for Call Assist"
                            } else {
                                "Not spoken aloud on this device"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } },
                actions = {
                    conversation?.let { c ->
                        TextButton(
                            onClick = {
                                val contact = viewModel.contacts.value.firstOrNull { it.userId == c.peerId }
                                if (contact != null) viewModel.setTrusted(contact, !c.peerTrusted)
                            },
                        ) {
                            Text(if (c.peerTrusted) "🔊" else "🔇")
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
                            "Speaking now…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
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
                            onValueChange = { draft = it },
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
                            ) { Text("🔊 Speak Now", style = MaterialTheme.typography.labelSmall) }
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
                        "🔊 Speak Now",
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
        !message.isMine -> listOfNotNull(time, message.spokenAt?.let { "read aloud" }).joinToString(" · ")
        message.state == MessageState.PENDING -> "$time · sending"
        message.state == MessageState.FAILED -> "$time · not sent"
        message.deliveryState == DeliveryState.READ -> "$time · read"
        message.deliveryState == DeliveryState.DELIVERED -> "$time · delivered"
        else -> "$time · sent"
    }
}
