package com.aistudio.core.model

@JvmInline
value class ConversationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation ID must not be blank" }
    }
}

@JvmInline
value class MessageId(val value: String) {
    init {
        require(value.isNotBlank()) { "Message ID must not be blank" }
    }
}

@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "Provider ID must not be blank" }
    }
}

@JvmInline
value class ModelId(val value: String) {
    init {
        require(value.isNotBlank()) { "Model ID must not be blank" }
    }
}

@JvmInline
value class ToolId(val value: String) {
    init {
        require(value.isNotBlank()) { "Tool ID must not be blank" }
    }
}

@JvmInline
value class ToolCallId(val value: String) {
    init {
        require(value.isNotBlank()) { "Tool call ID must not be blank" }
    }
}

@JvmInline
value class ApprovalId(val value: String) {
    init {
        require(value.isNotBlank()) { "Approval ID must not be blank" }
    }
}
