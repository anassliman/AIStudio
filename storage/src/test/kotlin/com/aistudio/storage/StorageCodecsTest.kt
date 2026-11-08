package com.aistudio.storage

import com.aistudio.core.model.ApprovalId
import com.aistudio.core.model.BootCount
import com.aistudio.core.model.ClockSnapshot
import com.aistudio.core.model.Conversation
import com.aistudio.core.model.ConversationError
import com.aistudio.core.model.ConversationId
import com.aistudio.core.model.ApprovalFailureCode
import com.aistudio.core.model.Message
import com.aistudio.core.model.MessageContent
import com.aistudio.core.model.MessageId
import com.aistudio.core.model.MessagePart
import com.aistudio.core.model.MessageRole
import com.aistudio.core.model.MessageStatus
import com.aistudio.core.model.ProviderError
import com.aistudio.core.model.ProviderId
import com.aistudio.core.model.RequestFingerprint
import com.aistudio.core.model.ToolArguments
import com.aistudio.core.model.ToolCallId
import com.aistudio.core.model.ToolId
import com.aistudio.core.model.ToolInvocation
import com.aistudio.core.model.ToolInvocationStatus
import com.aistudio.core.model.ToolRequest
import com.aistudio.core.model.ToolResult
import com.aistudio.core.model.PendingToolApproval
import com.aistudio.storage.codec.StorageCodecs
import com.aistudio.storage.codec.RestoredMessage
import com.aistudio.storage.codec.UnsupportedStoredDataException
import com.aistudio.storage.codec.toCore
import com.aistudio.storage.codec.toEntity
import com.aistudio.storage.codec.toRestoredMessage
import com.aistudio.storage.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorageCodecsTest {
    @Test
    fun conversationMessageInvocationAndNestedPayloadsRoundTrip() {
        val conversation = Conversation(
            id = ConversationId("conversation-1"),
            title = "Chat",
            createdAt = Instant.ofEpochSecond(100, 123_456_789),
            updatedAt = Instant.ofEpochSecond(101, 987_654_321),
            providerId = ProviderId("provider"),
            modelId = com.aistudio.core.model.ModelId("model"),
            archivedAt = Instant.ofEpochSecond(102, 1),
        )
        assertEquals(conversation, conversation.toEntity(nextSequence = 8).toCore())

        val request = ToolRequest(
            ToolCallId("call-1"),
            ToolId("search"),
            ToolArguments("{\"query\":\"hello\"}"),
        )
        val assistantMessage = Message(
            id = MessageId("message-1"),
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = MessageContent(listOf(MessagePart.Text("Searching"), MessagePart.ToolCall(request))),
            createdAt = Instant.ofEpochSecond(110, 5),
            sequence = 3,
            status = MessageStatus.FAILED,
            providerId = conversation.providerId,
            modelId = conversation.modelId,
            error = ConversationError.ProviderFailure(ProviderError.RateLimited(15)),
        )
        assertEquals(assistantMessage, assistantMessage.toEntity().toCore())
        assertEquals(RestoredMessage.Persisted(assistantMessage), assistantMessage.toEntity().toRestoredMessage())

        val toolMessage = Message(
            id = MessageId("tool-result-message"),
            conversationId = conversation.id,
            role = MessageRole.TOOL,
            content = MessageContent(listOf(MessagePart.ToolResult(ToolResult.Denied(request.callId)))),
            createdAt = Instant.ofEpochSecond(111, 6),
            sequence = 4,
        )
        assertEquals(request.callId.value, toolMessage.toEntity().toolCallId)
        assertEquals(toolMessage, toolMessage.toEntity().toCore())

        val approval = PendingToolApproval.create(
            id = ApprovalId("approval-1"),
            conversationId = conversation.id,
            request = request,
            now = ClockSnapshot(Instant.ofEpochSecond(120, 33), 500, BootCount.Known(4)),
            ttl = Duration.ofSeconds(30),
        )
        val invocation = ToolInvocation(
            conversationId = conversation.id,
            request = request,
            requestFingerprint = RequestFingerprint.calculate(conversation.id, request),
            status = ToolInvocationStatus.APPROVED,
            approval = approval,
        )
        assertEquals(invocation, invocation.toEntity().toCore())

        val result = ToolResult.Success(request.callId, com.aistudio.core.model.ToolPayload("{\"count\":2}"))
        assertEquals(result, StorageCodecs.decodeToolResult(StorageCodecs.RESULT_VERSION, StorageCodecs.encodeToolResult(result)))
        assertEquals(
            ConversationError.ProviderFailure(ProviderError.RateLimited(15)),
            StorageCodecs.decodeConversationError(
                StorageCodecs.ERROR_VERSION,
                StorageCodecs.encodeConversationError(ConversationError.ProviderFailure(ProviderError.RateLimited(15))),
            ),
        )
    }

    @Test
    fun unsupportedContentVersionAndTagFailWithoutChangingStoredRow() = runBlocking {
        val database = inMemoryDatabase()
        try {
            database.conversationDao().insert(conversation("conversation-1"))
            val raw = message("future-message", "conversation-1", 0).copy(
                contentFormatVersion = 99,
                contentJson = "{\"parts\":[{\"type\":\"future_part\"}]}",
            )
            database.messageDao().insert(raw)
            val unknownTag = message("future-tag-message", "conversation-1", 1).copy(
                contentJson = "{\"parts\":[{\"type\":\"future_part\"}]}",
            )
            database.messageDao().insert(unknownTag)

            assertFailsWith<UnsupportedStoredDataException> { raw.toCore() }
            assertFailsWith<UnsupportedStoredDataException> { unknownTag.toCore() }
            assertEquals(raw, database.messageDao().getById(raw.id))
            assertEquals(unknownTag, database.messageDao().getById(unknownTag.id))
        } finally {
            database.close()
        }
    }

    @Test
    fun unsupportedPartTagAndUnknownEnumTagAreRejectedExplicitly() {
        assertFailsWith<UnsupportedStoredDataException> {
            StorageCodecs.decodeMessageContent(
                StorageCodecs.CONTENT_VERSION,
                """{"parts":[{"type":"future_part"}]}""",
            )
        }
        assertFailsWith<UnsupportedStoredDataException> {
            StorageCodecs.messageStatusFromStorage("future_status")
        }
    }

    @Test
    fun everyToolResultAndConversationErrorVariantHasAnExplicitRoundTrip() {
        val callId = ToolCallId("call-x")
        val results = listOf(
            ToolResult.Success(callId, com.aistudio.core.model.ToolPayload("{\"ok\":true}")),
            ToolResult.Failure(callId, com.aistudio.core.model.ToolFailureCode.OUTPUT_LIMIT_EXCEEDED),
            ToolResult.Denied(callId),
        )
        results.forEach { result ->
            assertEquals(result, StorageCodecs.decodeToolResult(1, StorageCodecs.encodeToolResult(result)))
            val columns = StorageCodecs.encodeToolResultColumns(result)
            assertEquals(
                result,
                StorageCodecs.decodeToolResultColumns(
                    result.callId,
                    columns.type,
                    columns.payloadJson,
                    columns.failureCode,
                ),
            )
            val toolMessage = Message(
                id = MessageId("tool-${result.callId.value}-${columns.type}"),
                conversationId = ConversationId("conversation-x"),
                role = MessageRole.TOOL,
                content = MessageContent(listOf(MessagePart.ToolResult(result))),
                createdAt = Instant.ofEpochSecond(1),
                sequence = 0,
            )
            assertEquals(toolMessage, toolMessage.toEntity().toCore())
        }

        val errors = listOf(
            ConversationError.InvalidRequest("bad request"),
            ConversationError.ConversationNotFound(ConversationId("conversation-x")),
            ConversationError.ProviderNotFound(ProviderId("provider-x")),
            ConversationError.ProviderUnavailable(ProviderId("provider-x"), "offline"),
            ConversationError.ModelUnavailable(ProviderId("provider-x"), com.aistudio.core.model.ModelId("model-x")),
            ConversationError.ProviderFailure(ProviderError.Unavailable),
            ConversationError.ProviderFailure(ProviderError.AuthenticationRequired),
            ConversationError.ProviderFailure(ProviderError.RateLimited(7)),
            ConversationError.ProviderFailure(ProviderError.Rejected("rejected")),
            ConversationError.ProviderFailure(ProviderError.Unknown("unknown")),
            ConversationError.StorageFailure("disk error"),
            ConversationError.ToolRoundLimitExceeded,
            ConversationError.ToolCallLimitExceeded,
            ConversationError.ProcessInterrupted,
            ConversationError.ApprovalRejected(ApprovalFailureCode.ALREADY_CONSUMED),
        )
        errors.forEach { error ->
            assertEquals(error, StorageCodecs.decodeConversationError(1, StorageCodecs.encodeConversationError(error)))
        }
    }
}
