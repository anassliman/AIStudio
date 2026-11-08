package com.aistudio.storage.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ToolInvocationEntity::class,
            parentColumns = ["conversationId", "toolCallId"],
            childColumns = ["conversationId", "toolCallId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversationId", "sequence"], unique = true),
        // NULL toolCallId values are permitted for all non-TOOL messages by SQLite.
        Index(value = ["conversationId", "toolCallId"], unique = true),
        Index(value = ["status", "role"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sequence: Long,
    /** Stable storage tag (system/user/assistant/tool), never an enum ordinal. */
    val role: String,
    /** Null for TOOL messages, whose result is represented in dedicated relational columns. */
    val contentFormatVersion: Int?,
    val contentJson: String?,
    val createdAtEpochSeconds: Long,
    val createdAtNano: Int,
    /** Stable storage tag, never an enum ordinal. */
    val status: String,
    val providerId: String?,
    val modelId: String?,
    val errorFormatVersion: Int?,
    val errorJson: String?,
    /** Non-null only for a TOOL result message; points to its invocation. */
    val toolCallId: String?,
    /** Stable result discriminator, present only for TOOL messages. */
    val toolResultType: String?,
    /** Success payload JSON, present only for successful TOOL messages. */
    val toolResultPayloadJson: String?,
    /** Stable ToolFailureCode discriminator, present only for failed TOOL messages. */
    val toolFailureCode: String?,
)
