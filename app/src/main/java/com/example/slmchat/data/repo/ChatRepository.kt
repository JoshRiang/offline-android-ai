package com.example.slmchat.data.repo

import com.example.slmchat.data.local.Conversation
import com.example.slmchat.data.local.Message
import com.example.slmchat.data.local.MessageRole
import com.example.slmchat.data.local.SlmDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Repository over the Room database. Pure data access — LLM calls live in
 * [com.example.slmchat.llm.LlmManager] and are orchestrated from ViewModels.
 */
class ChatRepository(private val db: SlmDatabase) {

    private val conversations = db.conversationDao()
    private val messages = db.messageDao()

    fun observeConversations(): Flow<List<Conversation>> = conversations.observeAll()

    fun observeConversation(id: Long): Flow<Conversation?> = conversations.observeById(id)

    fun observeMessages(conversationId: Long): Flow<List<Message>> =
        messages.observeForConversation(conversationId)

    suspend fun createConversation(title: String = "New chat"): Long =
        conversations.insert(Conversation(title = title))

    suspend fun ensureConversation(id: Long?): Long {
        if (id != null && id > 0) {
            val existing = conversations.getById(id)
            if (existing != null) return id
        }
        return createConversation()
    }

    suspend fun addMessage(conversationId: Long, role: String, content: String): Long =
        messages.insert(
            Message(conversationId = conversationId, role = role, content = content)
        ).also {
            conversations.touch(conversationId)
        }

    suspend fun updateMessageContent(messageId: Long, content: String) =
        messages.updateContent(messageId, content)

    suspend fun recentMessages(conversationId: Long, limit: Int = 20): List<Message> =
        messages.getRecent(conversationId, limit).reversed()

    /** Auto-title a conversation from its first user message. */
    suspend fun maybeTitleFromFirstMessage(conversationId: Long, firstUserText: String) {
        val convo = conversations.getById(conversationId) ?: return
        if (convo.title == "New chat") {
            val title = firstUserText.trim().replace(Regex("\\s+"), " ").take(42)
            conversations.rename(conversationId, title.ifBlank { "New chat" })
        } else {
            conversations.touch(conversationId)
        }
    }

    suspend fun renameConversation(id: Long, title: String) =
        conversations.rename(id, title)

    suspend fun deleteConversation(id: Long) {
        messages.deleteForConversation(id)
        conversations.deleteById(id)
    }

    suspend fun clearAll() {
        messages.deleteAll()
        conversations.deleteAll()
    }

    companion object {
        /** Convenience for building a user message row without persisting yet. */
        fun userMessage(conversationId: Long, text: String) =
            Message(conversationId = conversationId, role = MessageRole.USER, content = text)

        fun assistantMessage(conversationId: Long, text: String = "") =
            Message(conversationId = conversationId, role = MessageRole.ASSISTANT, content = text)
    }
}
