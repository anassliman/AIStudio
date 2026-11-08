package com.aistudio.core.model

import java.time.Instant

data class Conversation(
    val id: ConversationId,
    val title: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val providerId: ProviderId? = null,
    val modelId: ModelId? = null,
    val archivedAt: Instant? = null,
) {
    init {
        require(title == null || title.isNotBlank()) { "Conversation title must not be blank" }
        require(updatedAt >= createdAt) { "Conversation update time must not precede creation time" }
        require(modelId == null || providerId != null) {
            "A selected model must belong to a selected provider"
        }
        require(archivedAt == null || archivedAt >= createdAt) {
            "Conversation archive time must not precede creation time"
        }
    }
}
