package com.aistudio.storage.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.aistudio.storage.entity.MessageEntity

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun listForConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND toolCallId = :toolCallId")
    suspend fun getToolResult(conversationId: String, toolCallId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status IN (:statuses) ORDER BY conversationId, sequence")
    suspend fun listWithStatuses(statuses: List<String>): List<MessageEntity>
}
