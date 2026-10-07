package com.airwhispers.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.airwhispers.data.model.Contact
import com.airwhispers.ui.AppViewModel
import com.airwhispers.ui.components.Avatar
import com.airwhispers.ui.components.EmptyState

@Composable
fun ContactsScreen(viewModel: AppViewModel, onOpenChat: (String) -> Unit) {
    val contacts by viewModel.contacts.collectAsState()
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    val filtered = contacts.filter {
        query.isBlank() ||
            it.displayName.contains(query, true) ||
            it.email.contains(query, true)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search contacts") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Text(
                "Contacts marked 🔊 may trigger spoken messages on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            if (filtered.isEmpty()) {
                EmptyState(
                    title = if (contacts.isEmpty()) "No contacts yet" else "No matches",
                    body = if (contacts.isEmpty()) {
                        "Add someone with the button in the corner — they need an AirWhispers account."
                    } else {
                        "Try a different name or email address."
                    },
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(filtered, key = { it.userId }) { contact ->
                        ContactRow(
                            contact = contact,
                            onToggleTrust = { viewModel.setTrusted(contact, it) },
                            onClick = {
                                viewModel.openConversationWith(contact.userId) { conversationId ->
                                    onOpenChat(conversationId)
                                }
                            },
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            shape = CircleShape,
        ) { Text("➕") }
    }

    if (showAdd) {
        var email by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add contact") },
            text = {
                Column {
                    Text("They must already have an AirWhispers account.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        label = { Text("Email") },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addContact(email) { err ->
                            if (err == null) showAdd = false else error = err.message
                        }
                    },
                    enabled = email.contains("@"),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ContactRow(contact: Contact, onToggleTrust: (Boolean) -> Unit, onClick: () -> Unit) {
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
            Avatar(contact.displayName, size = 40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    contact.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = contact.isTrusted, onCheckedChange = onToggleTrust)
        }
    }
}
