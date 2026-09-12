package com.example.slmchat

import android.app.Application
import com.example.slmchat.data.local.SlmDatabase
import com.example.slmchat.data.prefs.SettingsPreferences
import com.example.slmchat.data.repo.ChatRepository
import com.example.slmchat.llm.LlmManager
import com.example.slmchat.llm.MediaPipeLlmEngine

/**
 * Process-wide composition root.
 *
 * Owns the Room database, DataStore prefs, repository, inference engine and
 * [LlmManager] as singletons so Activities / ViewModels share one engine
 * instance (the native LLM library is single-session at a time).
 *
 * Declared in AndroidManifest.xml via `android:name=".SlmChatApp"`.
 */
class SlmChatApp : Application() {

    val database: SlmDatabase by lazy { SlmDatabase.getInstance(this) }

    val settingsPreferences: SettingsPreferences by lazy { SettingsPreferences(this) }

    val chatRepository: ChatRepository by lazy { ChatRepository(database) }

    val llmEngine by lazy { MediaPipeLlmEngine(this) }

    val llmManager: LlmManager by lazy {
        LlmManager(
            appContext = this,
            prefs = settingsPreferences,
            repository = chatRepository,
            engine = llmEngine
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Touch download states early so Chat/Settings screens observe values immediately.
        llmManager.refreshDownloadStates()
    }
}
