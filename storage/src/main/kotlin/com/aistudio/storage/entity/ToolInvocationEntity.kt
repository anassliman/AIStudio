package com.aistudio.storage.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "tool_invocations",
    primaryKeys = ["conversationId", "toolCallId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["approvalId"], unique = true),
        Index(value = ["conversationId", "status"]),
        Index(value = ["status", "approvalBootCount", "approvalExpiresElapsedRealtimeMillis"]),
    ],
)
data class ToolInvocationEntity(
    val conversationId: String,
    val toolCallId: String,
    val toolId: String,
    val argumentsJson: String,
    val requestFingerprint: String,
    val fingerprintVersion: Int,
    /** Stable storage tag, never an enum ordinal. */
    val status: String,
    val resultFormatVersion: Int?,
    val resultJson: String?,
    val approvalId: String?,
    val approvalCreatedAtEpochSeconds: Long?,
    val approvalCreatedAtNano: Int?,
    val approvalExpiresAtEpochSeconds: Long?,
    val approvalExpiresAtNano: Int?,
    val approvalCreatedElapsedRealtimeMillis: Long?,
    val approvalExpiresElapsedRealtimeMillis: Long?,
    val approvalBootCount: Long?,
)
