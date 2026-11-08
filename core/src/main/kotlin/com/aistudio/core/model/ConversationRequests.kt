package com.aistudio.core.model

import java.time.Instant

data class SendMessageRequest(
    val conversationId: ConversationId,
    val content: MessageContent,
    val providerId: ProviderId,
    val modelId: ModelId? = null,
    val options: ProviderOptions = ProviderOptions(),
)

sealed interface ConversationResult {
    data class Success(
        val conversation: Conversation,
        val userMessage: Message,
        val assistantMessage: Message,
    ) : ConversationResult {
        init {
            require(userMessage.conversationId == conversation.id)
            require(assistantMessage.conversationId == conversation.id)
            require(userMessage.role == MessageRole.USER)
            require(assistantMessage.role == MessageRole.ASSISTANT)
            require(userMessage.status == MessageStatus.COMPLETED)
            require(assistantMessage.status == MessageStatus.COMPLETED)
            require(assistantMessage.sequence > userMessage.sequence)
        }
    }

    data class Failure(val error: ConversationError) : ConversationResult

    data class WaitingForApproval(
        val conversationId: ConversationId,
        val approvalId: ApprovalId,
        val expiresAt: Instant,
    ) : ConversationResult
}
