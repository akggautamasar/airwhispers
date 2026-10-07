package com.airwhispers.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.airwhispers.AirWhispersApplication
import com.airwhispers.data.repo.IdentityStatus
import com.airwhispers.service.CallAssistService
import com.airwhispers.service.Notifier
import com.airwhispers.ui.screens.CallAssistScreen
import com.airwhispers.ui.screens.ChatScreen
import com.airwhispers.ui.screens.HomeScaffold
import com.airwhispers.ui.screens.OnboardingScreen
import com.airwhispers.ui.screens.SettingsScreen
import com.airwhispers.ui.theme.AirWhispersTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory((application as AirWhispersApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val conversationFromNotification = intent?.getStringExtra(Notifier.EXTRA_CONVERSATION_ID)
        setContent {
            AirWhispersTheme {
                AppRoot(
                    viewModel = viewModel,
                    openConversationId = conversationFromNotification,
                    startCallAssist = { startCallAssistService() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onVisible()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onHidden()
    }

    private fun startCallAssistService() {
        val intent = Intent(this, CallAssistService::class.java).apply { action = Notifier.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

private sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class Chat(val conversationId: String) : Screen
}

@Composable
private fun AppRoot(
    viewModel: AppViewModel,
    openConversationId: String?,
    startCallAssist: () -> Unit,
) {
    val session by viewModel.session.collectAsState()
    var screen by remember(openConversationId) {
        mutableStateOf<Screen>(
            if (openConversationId != null) Screen.Chat(openConversationId) else Screen.Home,
        )
    }

    // Notifications are how the user learns about a message we could not speak.
    RequestNotificationPermission()

    when (session.status) {
        // No identity yet: ask for a server and a name, then hand out the code.
        IdentityStatus.SETUP -> OnboardingScreen(viewModel)
        IdentityStatus.READY -> when (val current = screen) {
            is Screen.Chat -> ChatScreen(
                viewModel = viewModel,
                conversationId = current.conversationId,
                onBack = { screen = Screen.Home },
            )
            else -> HomeScaffold(
                viewModel = viewModel,
                startCallAssist = startCallAssist,
                onOpenChat = { conversationId -> screen = Screen.Chat(conversationId) },
            )
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Either way the app keeps working; spoken messages do not need it. */ }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

