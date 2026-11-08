package com.aistudio.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ContractTypesTest {
    @Test
    fun providerCallResultCarriesNormalizedSuccessAndFailure() {
        val success = ProviderCallResult.Success(
            ProviderResponse(turn = ProviderTurn.FinalAnswer(MessageContent.text("answer"))),
        )
        val failure = ProviderCallResult.Failure(ProviderError.RateLimited(retryAfterSeconds = 10))

        assertEquals(
            "answer",
            assertIs<ProviderTurn.FinalAnswer>(
                assertIs<ProviderCallResult.Success>(success).response.turn,
            ).content.text,
        )
        assertEquals(
            10L,
            assertIs<ProviderError.RateLimited>(
                assertIs<ProviderCallResult.Failure>(failure).error,
            ).retryAfterSeconds,
        )
    }

    @Test
    fun providerRequestsAndToolCallsSnapshotCallerCollections() {
        val id = ConversationId("conversation-1")
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val message = Message(
            id = MessageId("message-1"),
            conversationId = id,
            role = MessageRole.USER,
            content = MessageContent.text("question"),
            createdAt = at,
            sequence = 0,
        )
        val sourceMessages = mutableListOf(message)
        val request = ProviderRequest(id, null, sourceMessages)
        sourceMessages.clear()
        assertEquals(1, request.messages.size)
        assertFailsWith<UnsupportedOperationException> { (request.messages as MutableList).clear() }

        val call = ToolRequest(
            callId = ToolCallId("call-1"),
            toolId = ToolId("tool-1"),
            arguments = ToolArguments("{}"),
        )
        val sourceCalls = mutableListOf(call)
        val turn = ProviderTurn.ToolRequests(sourceCalls)
        sourceCalls.clear()
        assertEquals(1, turn.calls.size)
        assertFailsWith<UnsupportedOperationException> { (turn.calls as MutableList).clear() }
    }

    @Test
    fun conversationResultCarriesExchangeOrDomainFailure() {
        val id = ConversationId("conversation-1")
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val conversation = Conversation(id = id, createdAt = at)
        val user = Message(
            id = MessageId("user-1"),
            conversationId = id,
            role = MessageRole.USER,
            content = MessageContent.text("question"),
            createdAt = at,
            sequence = 0,
        )
        val assistant = user.copy(
            id = MessageId("assistant-1"),
            role = MessageRole.ASSISTANT,
            content = MessageContent.text("answer"),
            sequence = 1,
        )
        val success = ConversationResult.Success(conversation, user, assistant)
        val failure = ConversationResult.Failure(
            ConversationError.ProviderFailure(ProviderError.Unavailable),
        )

        assertEquals("answer", assertIs<ConversationResult.Success>(success).assistantMessage.content.text)
        assertFailsWith<IllegalArgumentException> {
            ConversationResult.Success(conversation, user, assistant.copy(sequence = user.sequence))
        }
        assertEquals(
            ConversationError.ProviderFailure(ProviderError.Unavailable),
            assertIs<ConversationResult.Failure>(failure).error,
        )
    }
}
