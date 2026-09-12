package com.example.slmchat.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Role constants for [Message.role]. Kept as String for Room simplicity. */
object MessageRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

/**
 * A single chat message. Streaming assistant replies are persisted as one row
 * whose [content] is updated as tokens arrive.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index("createdAt")]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
