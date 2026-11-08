package com.aistudio.storage.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["updatedAtEpochSeconds", "updatedAtNano"]),
        Index(value = ["archivedAtEpochSeconds", "updatedAtEpochSeconds", "updatedAtNano"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAtEpochSeconds: Long,
    val createdAtNano: Int,
    val updatedAtEpochSeconds: Long,
    val updatedAtNano: Int,
    val providerId: String?,
    val modelId: String?,
    val archivedAtEpochSeconds: Long?,
    val archivedAtNano: Int?,
    /** Persistence-owned counter. Phase C does not allocate or mutate sequence values. */
    val nextSequence: Long = 0,
)
