package com.aistudio.core.contract

import com.aistudio.core.model.Conversation
import com.aistudio.core.model.ConversationId
import com.aistudio.core.model.MessageDraft
import com.aistudio.core.model.Message
import com.aistudio.core.model.PendingToolApproval
import com.aistudio.core.model.MessageRole
import com.aistudio.core.model.MessageStatus
import com.aistudio.core.model.ApprovalClaimResult
import com.aistudio.core.model.ApprovalDecision
import com.aistudio.core.model.ApprovalId
import com.aistudio.core.model.ToolCallId
import com.aistudio.core.model.ToolInvocation
import com.aistudio.core.model.ToolInvocationRegistration
import com.aistudio.core.model.ToolInvocationStatus
import com.aistudio.core.model.ToolInvocationTransition

/**
 * Persistence boundary. Sequence allocation, turn writes, invocation registration, and approval
 * consumption must be atomic in an implementation. Contract-level tests do not prove SQLite behavior.
 * Approval expiry uses the implementation's trusted clock, never a timestamp from the caller.
 */
interface ConversationRepository {
    suspend fun getConversation(id: ConversationId): Conversation?

    suspend fun listConversations(includeArchived: Boolean = false): List<Conversation>

    suspend fun getMessages(conversationId: ConversationId): List<Message>

    /**
     * Atomically inserts the user and assistant messages, updates the conversation timestamp,
     * and assigns consecutive sequence values. The existing creation timestamp is preserved.
     * Same-conversation sends are serialized by the engine; sequence allocation is still atomic here.
     */
    suspend fun beginTurn(
        conversation: Conversation,
        userMessage: MessageDraft,
        assistantMessage: MessageDraft,
    ): StartedTurn

    /** Atomically assigns the next per-conversation sequence to a tool-call/result message. */
    suspend fun appendMessage(message: MessageDraft): Message

    /** Compare-and-set prevents late provider deltas from overwriting a terminal message. */
    suspend fun updateAssistantProgress(
        message: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult

    /** Terminal message and conversation metadata are committed together. */
    suspend fun completeTurn(
        conversation: Conversation,
        assistantMessage: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult
    suspend fun failTurn(
        conversation: Conversation,
        assistantMessage: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult
    suspend fun cancelTurn(
        conversation: Conversation,
        assistantMessage: Message,
        expectedStatus: MessageStatus,
    ): MessageMutationResult

    /**
     * Unique by approval ID and (conversationId, toolCallId). Implementations recompute/verify the
     * fingerprint; same request is idempotent, changed request is conflict.
     * New invocations must start PENDING_APPROVAL or APPROVED, never RUNNING; execution requires
     * the atomic APPROVED -> RUNNING transition.
     */
    suspend fun registerToolInvocation(invocation: ToolInvocation): ToolInvocationRegistration

    suspend fun getApproval(approvalId: ApprovalId): PendingToolApproval?

    suspend fun getToolInvocation(
        conversationId: ConversationId,
        callId: ToolCallId,
    ): ToolInvocation?

    /**
     * Compare-and-set transition; only the expected current state may be changed. In particular,
     * exactly one caller may claim APPROVED -> RUNNING before performing an external side effect.
     */
    suspend fun transitionToolInvocation(
        conversationId: ConversationId,
        callId: ToolCallId,
        expected: ToolInvocationStatus,
        updated: ToolInvocation,
    ): ToolInvocationTransition

    /**
     * Atomically validates binding, freshly recomputed fingerprint, expiry, and one-time consumption.
     * No fingerprint is accepted from the caller; implementations compare persisted fingerprints
     * against a fresh calculation from the persisted original request.
     */
    suspend fun consumeApproval(
        approvalId: ApprovalId,
        conversationId: ConversationId,
        decision: ApprovalDecision,
    ): ApprovalClaimResult

    /**
     * Marks abandoned provider work INTERRUPTED, ambiguous running tools UNKNOWN_AFTER_RESTART,
     * and expired approvals EXPIRED. APPROVED-but-not-RUNNING calls may resume only if approval
     * remains valid; external execution must start only after the atomic RUNNING claim.
     */
    suspend fun recoverInterruptedWork(): RecoveryReport
}

data class StartedTurn(
    val conversation: Conversation,
    val userMessage: Message,
    val assistantMessage: Message,
) {
    init {
        require(userMessage.conversationId == conversation.id)
        require(assistantMessage.conversationId == conversation.id)
        require(userMessage.role == MessageRole.USER && userMessage.status == MessageStatus.COMPLETED)
        require(assistantMessage.role == MessageRole.ASSISTANT && assistantMessage.status == MessageStatus.SENDING)
        require(assistantMessage.sequence == userMessage.sequence + 1)
    }
}

sealed interface MessageMutationResult {
    data class Updated(val message: Message) : MessageMutationResult
    data class Conflict(val currentStatus: MessageStatus?) : MessageMutationResult
}

data class RecoveryReport(
    val interruptedMessages: Int,
    val unknownToolOutcomes: Int,
    val expiredApprovals: Int,
)
