package com.aistudio.core.model

import java.time.Instant

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

enum class MessageStatus {
    PENDING,
    SENDING,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

sealed interface MessagePart {
    data class Text(val value: String) : MessagePart {
        init {
            require(value.isNotEmpty()) { "Text part must not be empty" }
        }
    }

    data class ToolCall(val request: ToolRequest) : MessagePart

    data class ToolResult(val result: com.aistudio.core.model.ToolResult) : MessagePart
}

class MessageContent(parts: Collection<MessagePart>) {
    val parts: List<MessagePart> = immutableListSnapshot(parts)

    init {
        require(this.parts.isNotEmpty()) { "Message content must contain at least one part" }
    }

    val text: String
        get() = parts.filterIsInstance<MessagePart.Text>().joinToString(separator = "") { it.value }

    companion object {
        fun text(value: String): MessageContent = MessageContent(listOf(MessagePart.Text(value)))
    }

    override fun equals(other: Any?): Boolean = other is MessageContent && parts == other.parts

    override fun hashCode(): Int = parts.hashCode()

    override fun toString(): String = "MessageContent(parts=$parts)"
}

data class MessageDraft(
    val id: MessageId,
    val conversationId: ConversationId,
    val role: MessageRole,
    val content: MessageContent,
    val createdAt: Instant,
    val status: MessageStatus,
) {
    init {
        require(role != MessageRole.TOOL ||
            (content.parts.size == 1 && content.parts.single() is MessagePart.ToolResult)
        ) {
            "Tool messages must contain exactly one tool result"
        }
        require(role == MessageRole.TOOL || content.parts.none { it is MessagePart.ToolResult }) {
            "Only tool messages may contain tool results"
        }
        require(role == MessageRole.ASSISTANT || content.parts.none { it is MessagePart.ToolCall }) {
            "Only assistant messages may contain tool calls"
        }
    }
}

data class Message(
    val id: MessageId,
    val conversationId: ConversationId,
    val role: MessageRole,
    val content: MessageContent,
    val createdAt: Instant,
    val sequence: Long,
    val status: MessageStatus = MessageStatus.COMPLETED,
    val providerId: ProviderId? = null,
    val modelId: ModelId? = null,
    val error: ConversationError? = null,
) {
    init {
        require(sequence >= 0) { "Message sequence must not be negative" }
        require((status == MessageStatus.FAILED || status == MessageStatus.INTERRUPTED) == (error != null)) {
            "Failed and interrupted messages must carry an error"
        }
        require(status != MessageStatus.CANCELLED || error == null) {
            "Cancelled messages must not carry a failure error"
        }
        val toolCalls = content.parts.count { it is MessagePart.ToolCall }
        val toolResults = content.parts.count { it is MessagePart.ToolResult }
        require(role == MessageRole.ASSISTANT || toolCalls == 0) {
            "Only assistant messages may contain tool calls"
        }
        require(role == MessageRole.TOOL && content.parts.size == 1 && toolResults == 1 ||
            role != MessageRole.TOOL && toolResults == 0
        ) {
            "Tool messages must contain exactly one tool result; other roles must not contain one"
        }
    }
}

fun MessageStatus.canTransitionTo(next: MessageStatus): Boolean = when (this) {
    MessageStatus.PENDING -> next in setOf(
        MessageStatus.SENDING,
        MessageStatus.COMPLETED,
        MessageStatus.FAILED,
        MessageStatus.CANCELLED,
        MessageStatus.INTERRUPTED,
    )
    MessageStatus.SENDING -> next in setOf(
        MessageStatus.STREAMING,
        MessageStatus.COMPLETED,
        MessageStatus.FAILED,
        MessageStatus.CANCELLED,
        MessageStatus.INTERRUPTED,
    )
    MessageStatus.STREAMING -> next in setOf(
        MessageStatus.COMPLETED,
        MessageStatus.FAILED,
        MessageStatus.CANCELLED,
        MessageStatus.INTERRUPTED,
    )
    MessageStatus.COMPLETED,
    MessageStatus.FAILED,
    MessageStatus.CANCELLED,
    MessageStatus.INTERRUPTED -> false
}
