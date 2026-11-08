package com.aistudio.storage

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.aistudio.storage.dao.ConversationDao
import com.aistudio.storage.dao.MessageDao
import com.aistudio.storage.dao.ToolInvocationDao
import com.aistudio.storage.entity.ConversationEntity
import com.aistudio.storage.entity.MessageEntity
import com.aistudio.storage.entity.ToolInvocationEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ToolInvocationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class StorageDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolInvocationDao(): ToolInvocationDao
}
