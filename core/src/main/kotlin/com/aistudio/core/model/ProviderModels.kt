package com.aistudio.core.model

data class ProviderCapabilities(
    val supportsStreaming: Boolean = false,
    val supportsTools: Boolean = false,
)

data class ProviderOptions(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(temperature == null || (temperature.isFinite() && temperature >= 0.0)) {
            "Temperature must be finite and non-negative"
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "Maximum output tokens must be positive"
        }
    }
}

class ProviderRequest(
    val conversationId: ConversationId,
    val modelId: ModelId?,
    messages: Collection<Message>,
    val options: ProviderOptions = ProviderOptions(),
) {
    val messages: List<Message> = immutableListSnapshot(messages)

    init {
        require(this.messages.isNotEmpty()) { "Provider request must contain messages" }
        require(this.messages.all { it.conversationId == conversationId }) {
            "All requested messages must belong to the requested conversation"
        }
        require(this.messages.map { it.id }.distinct().size == this.messages.size) {
            "Provider transcript message IDs must be unique"
        }
        require(this.messages.zipWithNext().all { (first, second) -> first.sequence < second.sequence }) {
            "Provider transcript must be strictly ordered by message sequence"
        }
        require(this.messages.none {
            it.status == MessageStatus.PENDING ||
                it.status == MessageStatus.SENDING ||
                it.status == MessageStatus.STREAMING
        }) { "Provider transcript must not contain in-flight messages" }
    }
}

data class ProviderResponse(
    val turn: ProviderTurn,
    val modelId: ModelId? = null,
    val usage: TokenUsage? = null,
    val finishReason: String? = null,
)

sealed interface ProviderTurn {
    data class FinalAnswer(val content: MessageContent) : ProviderTurn
    class ToolRequests(calls: Collection<ToolRequest>) : ProviderTurn {
        val calls: List<ToolRequest> = immutableListSnapshot(calls)

        init {
            require(this.calls.isNotEmpty()) { "Tool request turn must contain calls" }
            require(this.calls.map { it.callId }.distinct().size == this.calls.size) {
                "Tool call IDs must be unique within a provider turn"
            }
        }

        override fun equals(other: Any?): Boolean = other is ToolRequests && calls == other.calls
        override fun hashCode(): Int = calls.hashCode()
        override fun toString(): String = "ToolRequests(calls=$calls)"
    }
}

data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    init {
        require(inputTokens == null || inputTokens >= 0) { "Input token count must not be negative" }
        require(outputTokens == null || outputTokens >= 0) { "Output token count must not be negative" }
    }
}

sealed interface ProviderError {
    data object Unavailable : ProviderError
    data object AuthenticationRequired : ProviderError
    data class RateLimited(val retryAfterSeconds: Long? = null) : ProviderError {
        init {
            require(retryAfterSeconds == null || retryAfterSeconds >= 0) {
                "Retry delay must not be negative"
            }
        }
    }
    data class Rejected(val reason: String) : ProviderError {
        init {
            require(reason.isNotBlank()) { "Rejection reason must not be blank" }
        }
    }
    data class Unknown(val safeMessage: String? = null) : ProviderError
}

sealed interface ProviderCallResult {
    data class Success(val response: ProviderResponse) : ProviderCallResult
    data class Failure(val error: ProviderError) : ProviderCallResult
}

sealed interface ProviderUpdate {
    data class TextDelta(val text: String) : ProviderUpdate {
        init {
            require(text.isNotEmpty()) { "Text delta must not be empty" }
        }
    }
}

data class ProviderSelection(
    val providerId: ProviderId,
    val modelId: ModelId? = null,
)
