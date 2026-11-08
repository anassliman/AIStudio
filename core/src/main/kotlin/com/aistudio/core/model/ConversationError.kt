package com.aistudio.core.model

sealed interface ConversationError {
    data class InvalidRequest(val reason: String) : ConversationError {
        init {
            require(reason.isNotBlank()) { "Invalid request reason must not be blank" }
        }
    }

    data class ConversationNotFound(val conversationId: ConversationId) : ConversationError
    data class ProviderNotFound(val providerId: ProviderId) : ConversationError
    data class ProviderUnavailable(val providerId: ProviderId, val reason: String) : ConversationError
    data class ModelUnavailable(val providerId: ProviderId, val modelId: ModelId) : ConversationError
    data class ProviderFailure(val error: ProviderError) : ConversationError
    data class StorageFailure(val safeMessage: String? = null) : ConversationError
    data object ToolRoundLimitExceeded : ConversationError
    data object ToolCallLimitExceeded : ConversationError
    data object ProcessInterrupted : ConversationError
    data class ApprovalRejected(val reason: ApprovalFailureCode) : ConversationError
}

enum class ApprovalFailureCode {
    NOT_FOUND,
    WRONG_CONVERSATION,
    EXPIRED,
    FINGERPRINT_MISMATCH,
    ALREADY_CONSUMED,
}
