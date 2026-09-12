package com.example.slmchat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.slmchat.SlmChatApp
import androidx.compose.ui.platform.LocalContext
import com.example.slmchat.ui.chat.ChatScreen
import com.example.slmchat.ui.chat.ChatViewModel
import com.example.slmchat.ui.settings.SettingsScreen
import com.example.slmchat.ui.settings.SettingsViewModel

/** Navigation destinations for the app. */
object SlmRoutes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
}

/**
 * App-level [NavHost] wiring the chat and settings destinations.
 *
 * ViewModels are created with the process-wide singletons from [SlmChatApp]
 * so both destinations share one [com.example.slmchat.llm.LlmManager].
 */
@Composable
fun SlmNavGraph(
    navController: NavHostController = rememberNavController(),
    chatViewModel: ChatViewModel? = null,
    settingsViewModel: SettingsViewModel? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as SlmChatApp

    val chatVm: ChatViewModel = chatViewModel ?: viewModel(
        factory = ChatViewModel.Factory(app.chatRepository, app.settingsPreferences, app.llmManager)
    )
    val settingsVm: SettingsViewModel = settingsViewModel ?: viewModel(
        factory = SettingsViewModel.Factory(app.settingsPreferences, app.chatRepository, app.llmManager)
    )

    NavHost(
        navController = navController,
        startDestination = SlmRoutes.CHAT
    ) {
        composable(SlmRoutes.CHAT) {
            ChatScreen(
                viewModel = chatVm,
                onOpenSettings = { navController.navigate(SlmRoutes.SETTINGS) }
            )
        }
        composable(SlmRoutes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
