package com.aistudio.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelsTest {
    private val conversationId = ConversationId("conversation-1")
    private val createdAt = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun identifiersRejectBlankValues() {
        assertFailsWith<IllegalArgumentException> { ConversationId(" ") }
        assertFailsWith<IllegalArgumentException> { MessageId("") }
        assertFailsWith<IllegalArgumentException> { ProviderId("\t") }
        assertFailsWith<IllegalArgumentException> { ModelId("") }
    }

    @Test
    fun conversationRejectsTimesBeforeCreation() {
        assertFailsWith<IllegalArgumentException> {
            Conversation(
                id = conversationId,
                createdAt = createdAt,
                updatedAt = createdAt.minusSeconds(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Conversation(
                id = conversationId,
                createdAt = createdAt,
                archivedAt = createdAt.minusSeconds(1),
            )
        }
    }

    @Test
    fun conversationRequiresProviderWhenModelIsSelected() {
        assertFailsWith<IllegalArgumentException> {
            Conversation(
                id = conversationId,
                createdAt = createdAt,
                modelId = ModelId("model-1"),
            )
        }
        assertEquals(
            ModelId("model-1"),
            Conversation(
                id = conversationId,
                createdAt = createdAt,
                providerId = ProviderId("provider-1"),
                modelId = ModelId("model-1"),
            ).modelId,
        )
    }

    @Test
    fun messageContentJoinsTextPartsInOrder() {
        val content = MessageContent(
            listOf(MessagePart.Text("hello"), MessagePart.Text(" world")),
        )

        assertEquals("hello world", content.text)
    }

    @Test
    fun messageContentDefensivelyCopiesParts() {
        val source = mutableListOf<MessagePart>(MessagePart.Text("hello"))
        val content = MessageContent(source)
        source.clear()

        assertEquals("hello", content.text)
        assertFailsWith<UnsupportedOperationException> {
            (content.parts as MutableList).clear()
        }
    }

    @Test
    fun messageRequiresErrorExactlyWhenFailed() {
        val content = MessageContent.text("reply")

        assertFailsWith<IllegalArgumentException> {
            message(status = MessageStatus.FAILED, content = content)
        }
        assertFailsWith<IllegalArgumentException> {
            message(
                status = MessageStatus.COMPLETED,
                content = content,
                error = ConversationError.ProcessInterrupted,
            )
        }

        assertEquals(
            MessageStatus.FAILED,
            message(
                status = MessageStatus.FAILED,
                content = content,
                error = ConversationError.ProviderFailure(ProviderError.Unavailable),
            ).status,
        )
    }

    @Test
    fun messageLifecycleAllowsOnlyForwardTransitions() {
        assertTrue(MessageStatus.PENDING.canTransitionTo(MessageStatus.SENDING))
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.STREAMING))
        assertTrue(MessageStatus.STREAMING.canTransitionTo(MessageStatus.COMPLETED))
        assertTrue(MessageStatus.STREAMING.canTransitionTo(MessageStatus.CANCELLED))
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.INTERRUPTED))
        assertFalse(MessageStatus.COMPLETED.canTransitionTo(MessageStatus.SENDING))
        assertFalse(MessageStatus.CANCELLED.canTransitionTo(MessageStatus.COMPLETED))
    }

    @Test
    fun providerOptionsRejectInvalidValues() {
        assertFailsWith<IllegalArgumentException> { ProviderOptions(temperature = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ProviderOptions(temperature = -0.1) }
        assertFailsWith<IllegalArgumentException> { ProviderOptions(maxOutputTokens = 0) }
    }

    @Test
    fun providerRequestRequiresMessagesFromItsConversation() {
        val request = providerRequest(messages = listOf(message()))
        assertEquals(conversationId, request.conversationId)

        assertFailsWith<IllegalArgumentException> {
            providerRequest(
                messages = listOf(message(conversationId = ConversationId("another"))),
            )
        }
    }

    @Test
    fun providerRequestRequiresStableSequenceOrderAndNoInFlightMessages() {
        val first = message().copy(sequence = 1)
        val second = message().copy(id = MessageId("message-2"), sequence = 2)
        assertEquals(2, providerRequest(listOf(first, second)).messages.size)
        assertFailsWith<IllegalArgumentException> { providerRequest(listOf(second, first)) }
        assertFailsWith<IllegalArgumentException> {
            providerRequest(listOf(first, second.copy(status = MessageStatus.STREAMING)))
        }
    }

    @Test
    fun messageToolPartsMustMatchTheirRole() {
        val call = ToolRequest(ToolCallId("call"), ToolId("tool"), ToolArguments("{}"))
        val toolResult = com.aistudio.core.model.ToolResult.Success(
            ToolCallId("call"),
            ToolPayload("{\"ok\":true}"),
        )
        assertFailsWith<IllegalArgumentException> {
            MessageDraft(
                MessageId("bad-user-tool-call"), conversationId, MessageRole.USER,
                MessageContent(listOf(MessagePart.ToolCall(call))), createdAt, MessageStatus.COMPLETED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageDraft(
                MessageId("bad-tool-result"), conversationId, MessageRole.TOOL,
                MessageContent.text("missing structured result"), createdAt, MessageStatus.COMPLETED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MessageDraft(
                MessageId("tool-result-with-extra-text"), conversationId, MessageRole.TOOL,
                MessageContent(listOf(MessagePart.Text("extra"), MessagePart.ToolResult(toolResult))),
                createdAt, MessageStatus.COMPLETED,
            )
        }
        assertEquals(MessageRole.TOOL, MessageDraft(
            MessageId("good-tool-result"), conversationId, MessageRole.TOOL,
            MessageContent(listOf(MessagePart.ToolResult(toolResult))), createdAt, MessageStatus.COMPLETED,
        ).role)
        assertFailsWith<IllegalArgumentException> {
            Message(
                MessageId("message-tool-result-with-extra-text"), conversationId, MessageRole.TOOL,
                MessageContent(listOf(MessagePart.ToolResult(toolResult), MessagePart.Text("extra"))),
                createdAt, 0,
            )
        }
    }

    @Test
    fun tokenUsageRejectsNegativeCounts() {
        assertFailsWith<IllegalArgumentException> { TokenUsage(inputTokens = -1) }
        assertFailsWith<IllegalArgumentException> { TokenUsage(outputTokens = -1) }
    }

    private fun message(
        conversationId: ConversationId = this.conversationId,
        status: MessageStatus = MessageStatus.COMPLETED,
        content: MessageContent = MessageContent.text("hello"),
        error: ConversationError? = null,
    ) = Message(
        id = MessageId("message-1"),
        conversationId = conversationId,
        role = MessageRole.USER,
        content = content,
        createdAt = createdAt,
        sequence = 0,
        status = status,
        error = error,
    )

    private fun providerRequest(messages: List<Message>) = ProviderRequest(
        conversationId = conversationId,
        modelId = null,
        messages = messages,
    )
}
