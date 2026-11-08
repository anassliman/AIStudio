package com.aistudio.core.contract

import com.aistudio.core.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** In-memory contract fixture only; it does not stand in for Room/SQLite integration tests. */
internal class ContractRepository(private val clock: Clock) : ConversationRepository {
    constructor(now: Instant) : this(
        Clock {
            ClockSnapshot(
                wallTime = now,
                elapsedRealtimeMillis = now.toEpochMilli(),
                bootCount = BootCount.Known(1),
            )
        },
    )

    private val mutex = Mutex()
    private val conversations = mutableMapOf<ConversationId, Conversation>()
    private val messages = mutableMapOf<ConversationId, MutableList<Message>>()
    private val nextSequence = mutableMapOf<ConversationId, Long>()
    private val invocations = mutableMapOf<Pair<ConversationId, ToolCallId>, ToolInvocation>()

    override suspend fun getConversation(id: ConversationId): Conversation? = mutex.withLock {
        conversations[id]
    }

    override suspend fun listConversations(includeArchived: Boolean): List<Conversation> = mutex.withLock {
        conversations.values
            .filter { includeArchived || it.archivedAt == null }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    override suspend fun getMessages(conversationId: ConversationId): List<Message> = mutex.withLock {
        messages[conversationId].orEmpty().sortedBy { it.sequence }.toList()
    }

    override suspend fun beginTurn(
        conversation: Conversation,
        userMessage: MessageDraft,
        assistantMessage: MessageDraft,
    ): StartedTurn = mutex.withLock {
        require(userMessage.conversationId == conversation.id)
        require(assistantMessage.conversationId == conversation.id)
        require(userMessage.role == MessageRole.USER && userMessage.status == MessageStatus.COMPLETED)
        require(assistantMessage.role == MessageRole.ASSISTANT && assistantMessage.status == MessageStatus.SENDING)
        val firstSequence = nextSequence[conversation.id] ?: 0L
        nextSequence[conversation.id] = firstSequence + 2
        val user = userMessage.toMessage(firstSequence)
        val assistant = assistantMessage.toMessage(firstSequence + 1)
        conversations[conversation.id] = conversation
        messages.getOrPut(conversation.id) { mutableListOf() }.addAll(listOf(user, assistant))
        StartedTurn(conversation, user, assistant)
    }

    override suspend fun appendMessage(message: MessageDraft): Message = mutex.withLock {
        val sequence = nextSequence[message.conversationId] ?: 0L
        nextSequence[message.conversationId] = sequence + 1
        message.toMessage(sequence).also {
            messages.getOrPut(message.conversationId) { mutableListOf() }.add(it)
        }
    }

    override suspend fun updateAssistantProgress(
        message: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult = mutex.withLock {
        if (message.role != MessageRole.ASSISTANT || message.status != MessageStatus.STREAMING) {
            return@withLock MessageMutationResult.Conflict(null)
        }
        mutateMessage(message, expectedStatus)
    }

    override suspend fun completeTurn(
        conversation: Conversation,
        assistantMessage: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult = mutex.withLock {
        if (conversation.id != assistantMessage.conversationId || assistantMessage.role != MessageRole.ASSISTANT ||
            assistantMessage.status != MessageStatus.COMPLETED
        ) {
            return@withLock MessageMutationResult.Conflict(null)
        }
        val result = mutateMessage(assistantMessage, expectedStatus)
        if (result is MessageMutationResult.Updated) conversations[conversation.id] = conversation
        result
    }

    override suspend fun failTurn(
        conversation: Conversation,
        assistantMessage: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult = mutex.withLock {
        if (conversation.id != assistantMessage.conversationId || assistantMessage.role != MessageRole.ASSISTANT ||
            assistantMessage.status != MessageStatus.FAILED
        ) {
            return@withLock MessageMutationResult.Conflict(null)
        }
        val result = mutateMessage(assistantMessage, expectedStatus)
        if (result is MessageMutationResult.Updated) conversations[conversation.id] = conversation
        result
    }

    override suspend fun cancelTurn(
        conversation: Conversation,
        assistantMessage: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult = mutex.withLock {
        if (conversation.id != assistantMessage.conversationId || assistantMessage.role != MessageRole.ASSISTANT ||
            assistantMessage.status != MessageStatus.CANCELLED
        ) {
            return@withLock MessageMutationResult.Conflict(null)
        }
        val result = mutateMessage(assistantMessage, expectedStatus)
        if (result is MessageMutationResult.Updated) conversations[conversation.id] = conversation
        result
    }

    override suspend fun registerToolInvocation(
        invocation: ToolInvocation,
    ): ToolInvocationRegistration = mutex.withLock {
        if (invocation.status != ToolInvocationStatus.PENDING_APPROVAL &&
            invocation.status != ToolInvocationStatus.APPROVED
        ) {
            return@withLock ToolInvocationRegistration.RequestConflict
        }
        if (!invocation.requestFingerprint.matches(invocation.conversationId, invocation.request) ||
            invocation.approval?.isBoundToOriginalRequest() == false
        ) {
            return@withLock ToolInvocationRegistration.RequestConflict
        }
        val key = invocation.conversationId to invocation.request.callId
        val approvalId = invocation.approval?.id
        if (approvalId != null && invocations.any { (existingKey, existing) ->
                existingKey != key && existing.approval?.id == approvalId
            }
        ) {
            return@withLock ToolInvocationRegistration.RequestConflict
        }
        val existing = invocations[key]
        when {
            existing == null -> {
                invocations[key] = invocation
                ToolInvocationRegistration.Created(invocation)
            }
            existing.requestFingerprint == invocation.requestFingerprint ->
                ToolInvocationRegistration.Existing(existing)
            else -> ToolInvocationRegistration.RequestConflict
        }
    }

    override suspend fun getApproval(approvalId: ApprovalId): PendingToolApproval? = mutex.withLock {
        invocations.values.firstOrNull { it.approval?.id == approvalId }?.approval
    }

    override suspend fun getToolInvocation(
        conversationId: ConversationId,
        callId: ToolCallId,
    ): ToolInvocation? = mutex.withLock { invocations[conversationId to callId] }

    override suspend fun transitionToolInvocation(
        conversationId: ConversationId,
        callId: ToolCallId,
        expected: ToolInvocationStatus,
        updated: ToolInvocation,
    ): ToolInvocationTransition = mutex.withLock {
        val key = conversationId to callId
        val current = invocations[key]
        if (current == null || current.status != expected) {
            return@withLock ToolInvocationTransition.StateConflict(current?.status)
        }
        require(updated.conversationId == conversationId && updated.request.callId == callId)
        require(current.status.canTransitionTo(updated.status))
        require(updated.request == current.request)
        require(updated.requestFingerprint == current.requestFingerprint)
        require(updated.approval == current.approval)
        if (expected == ToolInvocationStatus.APPROVED && updated.status == ToolInvocationStatus.RUNNING &&
            current.approval?.isExpiredAt(clock.snapshot()) == true
        ) {
            invocations[key] = current.copy(status = ToolInvocationStatus.EXPIRED)
            return@withLock ToolInvocationTransition.StateConflict(ToolInvocationStatus.EXPIRED)
        }
        invocations[key] = updated
        ToolInvocationTransition.Updated(updated)
    }

    override suspend fun consumeApproval(
        approvalId: ApprovalId,
        conversationId: ConversationId,
        decision: ApprovalDecision,
    ): ApprovalClaimResult = mutex.withLock {
        val entry = invocations.entries.firstOrNull { it.value.approval?.id == approvalId }
            ?: return@withLock ApprovalClaimResult.NotFound
        val current = entry.value
        val approval = current.approval ?: return@withLock ApprovalClaimResult.NotFound
        if (approval.conversationId != conversationId || current.conversationId != conversationId) {
            return@withLock ApprovalClaimResult.WrongConversation
        }
        if (!current.requestFingerprint.matches(current.conversationId, current.request) ||
            current.requestFingerprint != approval.requestFingerprint
        ) {
            return@withLock ApprovalClaimResult.FingerprintMismatch
        }
        when (approval.validateFor(conversationId, clock.snapshot())) {
            ApprovalValidationResult.WrongConversation -> return@withLock ApprovalClaimResult.WrongConversation
            ApprovalValidationResult.FingerprintMismatch -> return@withLock ApprovalClaimResult.FingerprintMismatch
            ApprovalValidationResult.Expired -> {
                if (current.status == ToolInvocationStatus.PENDING_APPROVAL ||
                    current.status == ToolInvocationStatus.APPROVED
                ) {
                    invocations[entry.key] = current.copy(status = ToolInvocationStatus.EXPIRED)
                }
                return@withLock ApprovalClaimResult.Expired
            }
            is ApprovalValidationResult.Valid -> Unit
        }
        if (current.status != ToolInvocationStatus.PENDING_APPROVAL) {
            return@withLock ApprovalClaimResult.AlreadyConsumed
        }

        val claimed = when (decision) {
            ApprovalDecision.APPROVE -> current.copy(status = ToolInvocationStatus.APPROVED)
            ApprovalDecision.DENY -> current.copy(
                status = ToolInvocationStatus.DENIED,
                result = ToolResult.Denied(current.request.callId),
            )
        }
        invocations[entry.key] = claimed
        ApprovalClaimResult.Claimed(claimed)
    }

    override suspend fun recoverInterruptedWork(): RecoveryReport = mutex.withLock {
        var interrupted = 0
        messages.values.forEach { conversationMessages ->
            conversationMessages.indices.forEach { index ->
                val message = conversationMessages[index]
                if (message.role == MessageRole.ASSISTANT &&
                    (message.status == MessageStatus.SENDING || message.status == MessageStatus.STREAMING)
                ) {
                    conversationMessages[index] = message.copy(
                        status = MessageStatus.INTERRUPTED,
                        error = ConversationError.ProcessInterrupted,
                    )
                    interrupted++
                }
            }
        }
        var unknown = 0
        invocations.entries.toList().forEach { (key, invocation) ->
            if (invocation.status == ToolInvocationStatus.RUNNING) {
                invocations[key] = invocation.copy(status = ToolInvocationStatus.UNKNOWN_AFTER_RESTART)
                unknown++
            }
        }
        var expired = 0
        val now = clock.snapshot()
        invocations.entries.toList().forEach { (key, invocation) ->
            if ((invocation.status == ToolInvocationStatus.PENDING_APPROVAL ||
                    invocation.status == ToolInvocationStatus.APPROVED) &&
                invocation.approval?.isExpiredAt(now) == true
            ) {
                invocations[key] = invocation.copy(status = ToolInvocationStatus.EXPIRED)
                expired++
            }
        }
        RecoveryReport(interrupted, unknown, expired)
    }

    private fun mutateMessage(updated: Message, expectedStatus: MessageStatus): MessageMutationResult {
        val target = messages[updated.conversationId]
            ?: return MessageMutationResult.Conflict(null)
        val index = target.indexOfFirst { it.id == updated.id }
        if (index < 0) return MessageMutationResult.Conflict(null)
        val current = target[index]
        if (current.sequence != updated.sequence || current.status != expectedStatus ||
            !current.status.canTransitionTo(updated.status)
        ) {
            return MessageMutationResult.Conflict(current.status)
        }
        target[index] = updated
        return MessageMutationResult.Updated(updated)
    }

    private fun MessageDraft.toMessage(sequence: Long) = Message(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        createdAt = createdAt,
        sequence = sequence,
        status = status,
    )
}
