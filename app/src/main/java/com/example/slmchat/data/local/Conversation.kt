package com.example.slmchat.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single chat thread. Messages belong to exactly one conversation.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
