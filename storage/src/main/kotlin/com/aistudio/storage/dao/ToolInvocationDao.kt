package com.aistudio.storage.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.aistudio.storage.entity.ToolInvocationEntity

@Dao
interface ToolInvocationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(invocation: ToolInvocationEntity)

    @Query("SELECT * FROM tool_invocations WHERE conversationId = :conversationId AND toolCallId = :toolCallId")
    suspend fun getByCallId(conversationId: String, toolCallId: String): ToolInvocationEntity?

    @Query("SELECT * FROM tool_invocations WHERE approvalId = :approvalId")
    suspend fun getByApprovalId(approvalId: String): ToolInvocationEntity?

    @Query("SELECT * FROM tool_invocations WHERE conversationId = :conversationId ORDER BY toolCallId")
    suspend fun listForConversation(conversationId: String): List<ToolInvocationEntity>
}
