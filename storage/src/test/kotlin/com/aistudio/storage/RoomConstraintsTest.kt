package com.aistudio.storage

import com.aistudio.core.model.ConversationId
import com.aistudio.core.model.MessageContent
import com.aistudio.core.model.MessageDraft
import com.aistudio.core.model.MessageId
import com.aistudio.core.model.Message
import com.aistudio.core.model.MessagePart
import com.aistudio.core.model.MessageRole
import com.aistudio.core.model.MessageStatus
import com.aistudio.core.model.ToolCallId
import com.aistudio.core.model.ToolFailureCode
import com.aistudio.core.model.ToolPayload
import com.aistudio.core.model.ToolResult
import com.aistudio.storage.codec.StorageCodecs
import com.aistudio.storage.codec.RestoredMessage
import com.aistudio.storage.codec.toEntity
import com.aistudio.storage.codec.toCore
import com.aistudio.storage.entity.MessageEntity
import com.aistudio.storage.entity.ToolInvocationEntity
import com.aistudio.storage.dao.getRestoredById
import com.aistudio.storage.dao.listRestoredForConversation
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class RoomConstraintsTest {
    @Test
    fun sqliteAcceptsFailedAndInterruptedDraftsWithoutErrorsThroughMapperAndDao() = runBlocking {
        val database = inMemoryDatabase()
        try {
            database.conversationDao().insert(conversation("conversation-1"))
            val drafts = listOf(
                MessageDraft(
                    id = MessageId("failed-draft"),
                    conversationId = ConversationId("conversation-1"),
                    role = MessageRole.ASSISTANT,
                    content = MessageContent.text("failed"),
                    createdAt = Instant.ofEpochSecond(11),
                    status = MessageStatus.FAILED,
                ) to 0L,
                MessageDraft(
                    id = MessageId("interrupted-draft"),
                    conversationId = ConversationId("conversation-1"),
                    role = MessageRole.ASSISTANT,
                    content = MessageContent.text("interrupted"),
                    createdAt = Instant.ofEpochSecond(12),
                    status = MessageStatus.INTERRUPTED,
                ) to 1L,
            )

            drafts.forEach { (draft, sequence) ->
                val entity = draft.toEntity(sequence)
                assertEquals(null, entity.errorFormatVersion)
                assertEquals(null, entity.errorJson)
                database.messageDao().insert(entity)
                assertEquals(entity, database.messageDao().getById(entity.id))
                assertEquals(
                    RestoredMessage.Draft(draft, sequence, providerId = null, modelId = null),
                    database.messageDao().getRestoredById(entity.id),
                )
                assertFailsWith<IllegalArgumentException> { entity.toCore() }
            }
            assertEquals(
                drafts.map { (draft, sequence) ->
                    RestoredMessage.Draft(draft, sequence, providerId = null, modelId = null)
                },
                database.messageDao().listRestoredForConversation("conversation-1"),
            )

            assertFails {
                runBlocking {
                    database.messageDao().insert(
                        message("completed-with-error", "conversation-1", 2).copy(
                            errorFormatVersion = StorageCodecs.ERROR_VERSION,
                            errorJson = "{}",
                        ),
                    )
                }
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun foreignKeysRejectOrphanMessagesAndConversationPurgeCascades() = runBlocking {
        val database = inMemoryDatabase()
        try {
            assertFails {
                runBlocking { database.messageDao().insert(message("orphan", "missing", 0)) }
            }

            database.conversationDao().insert(conversation("conversation-1"))
            database.messageDao().insert(message("message-1", "conversation-1", 0))
            database.toolInvocationDao().insert(invocation("conversation-1", "call-1"))
            database.messageDao().insert(toolResultMessage("result-1", "conversation-1", "call-1", 1))
            assertEquals(1, database.conversationDao().deletePermanentlyById("conversation-1"))
            assertEquals(null, database.messageDao().getById("message-1"))
            assertEquals(null, database.messageDao().getById("result-1"))
            assertEquals(null, database.toolInvocationDao().getByCallId("conversation-1", "call-1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun uniqueConversationSequenceAndToolResultCallIdPreventDuplicates() = runBlocking {
        val database = inMemoryDatabase()
        try {
            database.conversationDao().insert(conversation("conversation-1"))
            database.messageDao().insert(message("message-1", "conversation-1", 0))
            assertFails {
                runBlocking { database.messageDao().insert(message("message-duplicate-sequence", "conversation-1", 0)) }
            }

            database.toolInvocationDao().insert(invocation("conversation-1", "call-1"))
            val firstResult = toolResultMessage("result-1", "conversation-1", "call-1", 1)
            database.messageDao().insert(firstResult)
            assertFails {
                runBlocking {
                    database.messageDao().insert(toolResultMessage("result-2", "conversation-1", "call-1", 2))
                }
            }
            assertEquals(firstResult, database.messageDao().getToolResult("conversation-1", "call-1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun sqliteToolResultMessagesRoundTripSuccessFailureAndDeniedThroughDao() = runBlocking {
        val database = inMemoryDatabase()
        try {
            val conversationId = "conversation-results"
            database.conversationDao().insert(conversation(conversationId))
            val results = listOf(
                ToolResult.Success(ToolCallId("call-success"), ToolPayload("{\"answer\":42}")),
                ToolResult.Failure(ToolCallId("call-failure"), ToolFailureCode.EXECUTION_FAILED),
                ToolResult.Denied(ToolCallId("call-denied")),
            )

            results.forEachIndexed { sequence, result ->
                database.toolInvocationDao().insert(
                    invocation(conversationId, result.callId.value),
                )
                val expected = Message(
                    id = MessageId("result-${result.callId.value}"),
                    conversationId = ConversationId(conversationId),
                    role = MessageRole.TOOL,
                    content = MessageContent(listOf(MessagePart.ToolResult(result))),
                    createdAt = Instant.ofEpochSecond(20L + sequence),
                    sequence = sequence.toLong(),
                )

                database.messageDao().insert(expected.toEntity())

                val restored = database.messageDao().getRestoredById(expected.id.value)
                assertEquals(RestoredMessage.Persisted(expected), restored)
                assertEquals(
                    expected,
                    (database.messageDao().listRestoredForConversation(conversationId)[sequence]
                        as RestoredMessage.Persisted).message,
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun sqliteRejectsToolMessagesWithoutInvocationOrWithInvocationFromAnotherConversation() = runBlocking {
        val database = inMemoryDatabase()
        try {
            database.conversationDao().insert(conversation("conversation-1"))
            database.conversationDao().insert(conversation("conversation-2"))
            database.toolInvocationDao().insert(invocation("conversation-1", "call-1"))

            assertFails {
                runBlocking {
                    database.messageDao().insert(toolResultMessage("orphan-tool", "conversation-1", null, 0))
                }
            }
            assertFails {
                runBlocking {
                    database.messageDao().insert(toolResultMessage("cross-conversation", "conversation-2", "call-1", 0))
                }
            }

            val valid = toolResultMessage("valid-tool", "conversation-1", "call-1", 0)
            database.messageDao().insert(valid)
            assertEquals(valid, database.messageDao().getToolResult("conversation-1", "call-1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun sqliteRejectsInvalidSequenceTimestampAndPartialApprovalRows() = runBlocking {
        val database = inMemoryDatabase()
        try {
            assertFails {
                runBlocking {
                    database.conversationDao().insert(conversation("bad-next-sequence").copy(nextSequence = -1))
                }
            }
            val validConversation = conversation("conversation-1")
            database.conversationDao().insert(validConversation)

            assertFails {
                runBlocking { database.messageDao().insert(message("negative-sequence", "conversation-1", -1)) }
            }
            assertFails {
                runBlocking {
                    database.messageDao().insert(message("bad-nanos", "conversation-1", 0).copy(createdAtNano = 1_000_000_000))
                }
            }
            assertFails {
                runBlocking {
                    database.toolInvocationDao().insert(
                        invocation("conversation-1", "partial-approval", "approval-partial")
                            .copy(approvalBootCount = null),
                    )
                }
            }

            assertEquals(validConversation, database.conversationDao().getById("conversation-1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun invocationPrimaryKeyAndApprovalIdAreUnique() = runBlocking {
        val database = inMemoryDatabase()
        try {
            database.conversationDao().insert(conversation("conversation-1"))
            database.toolInvocationDao().insert(invocation("conversation-1", "call-1", "approval-1"))
            assertFails {
                runBlocking {
                    database.toolInvocationDao().insert(invocation("conversation-1", "call-1", "approval-2"))
                }
            }
            assertFails {
                runBlocking {
                    database.toolInvocationDao().insert(invocation("conversation-1", "call-2", "approval-1"))
                }
            }
        } finally {
            database.close()
        }
    }

    private fun invocation(conversationId: String, callId: String, approvalId: String? = null) = ToolInvocationEntity(
        conversationId = conversationId,
        toolCallId = callId,
        toolId = "test-tool",
        argumentsJson = "{}",
        requestFingerprint = "a".repeat(64),
        fingerprintVersion = 1,
        status = "running",
        resultFormatVersion = null,
        resultJson = null,
        approvalId = approvalId,
        approvalCreatedAtEpochSeconds = approvalId?.let { 10 },
        approvalCreatedAtNano = approvalId?.let { 0 },
        approvalExpiresAtEpochSeconds = approvalId?.let { 20 },
        approvalExpiresAtNano = approvalId?.let { 0 },
        approvalCreatedElapsedRealtimeMillis = approvalId?.let { 5 },
        approvalExpiresElapsedRealtimeMillis = approvalId?.let { 15 },
        approvalBootCount = approvalId?.let { 1 },
    )

    private fun toolResultMessage(id: String, conversationId: String, callId: String?, sequence: Long) = MessageEntity(
        id = id,
        conversationId = conversationId,
        sequence = sequence,
        role = "tool",
        contentFormatVersion = null,
        contentJson = null,
        createdAtEpochSeconds = 11,
        createdAtNano = 0,
        status = "completed",
        providerId = null,
        modelId = null,
        errorFormatVersion = null,
        errorJson = null,
        toolCallId = callId,
        toolResultType = "denied",
        toolResultPayloadJson = null,
        toolFailureCode = null,
    )
}
