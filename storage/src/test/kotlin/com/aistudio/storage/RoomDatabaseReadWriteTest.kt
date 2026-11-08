package com.aistudio.storage

import com.aistudio.storage.entity.ConversationEntity
import com.aistudio.storage.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RoomDatabaseReadWriteTest {
    @Test
    fun bundledDriverCreatesInMemoryRoomDatabaseAndRoundTripsRows() = runBlocking {
        val database = TestStorageDatabaseFactory.inMemoryBuilder().build()
        try {
            val conversation = conversation("conversation-1")
            database.conversationDao().insert(conversation)
            database.messageDao().insert(message("message-1", "conversation-1", sequence = 0))

            assertEquals(conversation, database.conversationDao().getById("conversation-1"))
            assertNotNull(database.messageDao().getById("message-1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun messagesAreReadInSequenceOrder() = runBlocking {
        val database = inMemoryDatabase()
        try {
            database.conversationDao().insert(conversation("conversation-1"))
            database.messageDao().insert(message("message-2", "conversation-1", sequence = 2))
            database.messageDao().insert(message("message-0", "conversation-1", sequence = 0))
            database.messageDao().insert(message("message-1", "conversation-1", sequence = 1))

            assertEquals(
                listOf("message-0", "message-1", "message-2"),
                database.messageDao().listForConversation("conversation-1").map { it.id },
            )
        } finally {
            database.close()
        }
    }
}

internal fun inMemoryDatabase(): StorageDatabase =
    TestStorageDatabaseFactory.inMemoryBuilder().build()

internal fun conversation(id: String) = ConversationEntity(
    id = id,
    title = null,
    createdAtEpochSeconds = 10,
    createdAtNano = 123,
    updatedAtEpochSeconds = 10,
    updatedAtNano = 123,
    providerId = null,
    modelId = null,
    archivedAtEpochSeconds = null,
    archivedAtNano = null,
    nextSequence = 0,
)

internal fun message(id: String, conversationId: String, sequence: Long) = MessageEntity(
    id = id,
    conversationId = conversationId,
    sequence = sequence,
    role = "user",
    contentFormatVersion = 1,
    contentJson = """{"parts":[{"type":"text","value":"hello"}]}""",
    createdAtEpochSeconds = 11,
    createdAtNano = 0,
    status = "completed",
    providerId = null,
    modelId = null,
    errorFormatVersion = null,
    errorJson = null,
    toolCallId = null,
    toolResultType = null,
    toolResultPayloadJson = null,
    toolFailureCode = null,
)
