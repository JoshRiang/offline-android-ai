package com.example.slmchat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(conversationId: Long, limit: Int): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
