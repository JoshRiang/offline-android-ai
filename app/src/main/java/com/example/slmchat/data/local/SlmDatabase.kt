package com.example.slmchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Conversation::class, Message::class],
    version = 1,
    exportSchema = false
)
abstract class SlmDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var instance: SlmDatabase? = null

        fun getInstance(context: Context): SlmDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SlmDatabase::class.java,
                    "slm_chat.db"
                ).build().also { instance = it }
            }
    }
}
