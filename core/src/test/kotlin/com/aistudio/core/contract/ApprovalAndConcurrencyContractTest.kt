package com.aistudio.core.contract

import com.aistudio.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApprovalAndConcurrencyContractTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private val conversationId = ConversationId("conversation-1")
    private val toolRequest = request()

    @Test
    fun fingerprintIsDeterministicAndCoversEveryBoundField() {
        val fingerprint = RequestFingerprint.calculate(conversationId, toolRequest)

        assertEquals(fingerprint, RequestFingerprint.calculate(conversationId, toolRequest))
        assertEquals(64, fingerprint.sha256Hex.length)
        assertNotEquals(fingerprint, RequestFingerprint.calculate(ConversationId("other"), toolRequest))
        assertNotEquals(fingerprint, RequestFingerprint.calculate(conversationId, request(callId = "other-call")))
        assertNotEquals(fingerprint, RequestFingerprint.calculate(conversationId, request(toolId = "other-tool")))
        assertNotEquals(fingerprint, RequestFingerprint.calculate(conversationId, request(arguments = "{\"x\":2}")))
        assertNotEquals(
            RequestFingerprint.calculate(conversationId, request(callId = "a", toolId = "bc")),
            RequestFingerprint.calculate(conversationId, request(callId = "ab", toolId = "c")),
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            RequestFingerprint.fromPersisted("not-a-sha256")
        }
    }

    @Test
    fun approvalValidationBindsConversationRequestAndExpiry() {
        val approval = PendingToolApproval.create(
            id = ApprovalId("approval-1"),
            conversationId = conversationId,
            request = toolRequest,
            createdAt = now,
            expiresAt = now.plusSeconds(60),
        )

        assertIs<ApprovalValidationResult.Valid>(approval.validateFor(conversationId, now))
        assertEquals(
            ApprovalValidationResult.WrongConversation,
            approval.validateFor(ConversationId("other"), now),
        )
        assertEquals(
            ApprovalValidationResult.Expired,
            approval.validateFor(conversationId, now.plusSeconds(60)),
        )

        val changedRequest = request(arguments = "{\"x\":2}")
        val alteredRecord = approval.copy(request = changedRequest)
        assertEquals(
            ApprovalValidationResult.FingerprintMismatch,
            alteredRecord.validateFor(conversationId, now),
        )
    }

    @Test
    fun approvalConsumptionIsAtomicOneTimeAndDenialIsRecorded() = runTest {
        val repository = ContractRepository(now)
        val approval = pendingApproval()
        val invocation = pendingInvocation(approval)
        assertIs<ToolInvocationRegistration.Created>(repository.registerToolInvocation(invocation))
        assertIs<ToolInvocationRegistration.Existing>(repository.registerToolInvocation(invocation))
        val changedRequest = request(arguments = "{\"x\":2}")
        assertEquals(
            ToolInvocationRegistration.RequestConflict,
            repository.registerToolInvocation(
                ToolInvocation(
                    conversationId = conversationId,
                    request = changedRequest,
                    requestFingerprint = RequestFingerprint.calculate(conversationId, changedRequest),
                    status = ToolInvocationStatus.APPROVED,
                ),
            ),
        )

        val outcomes = (1..2).map {
            async(Dispatchers.Default) {
                repository.consumeApproval(
                    approvalId = approval.id,
                    conversationId = conversationId,
                    decision = ApprovalDecision.APPROVE,
                )
            }
        }.awaitAll()

        assertEquals(1, outcomes.count { it is ApprovalClaimResult.Claimed })
        assertEquals(1, outcomes.count { it == ApprovalClaimResult.AlreadyConsumed })
        assertEquals(
            ToolInvocationStatus.APPROVED,
            repository.getToolInvocation(conversationId, toolRequest.callId)?.status,
        )
        val approved = repository.getToolInvocation(conversationId, toolRequest.callId)!!
        val executionClaims = (1..2).map {
            async(Dispatchers.Default) {
                repository.transitionToolInvocation(
                    conversationId,
                    toolRequest.callId,
                    ToolInvocationStatus.APPROVED,
                    approved.copy(status = ToolInvocationStatus.RUNNING),
                )
            }
        }.awaitAll()
        assertEquals(1, executionClaims.count { it is ToolInvocationTransition.Updated })
        assertEquals(1, executionClaims.count { it is ToolInvocationTransition.StateConflict })

        val deniedRepo = ContractRepository(now)
        val deniedApproval = pendingApproval(id = "approval-denied", callId = "call-denied")
        deniedRepo.registerToolInvocation(pendingInvocation(deniedApproval))
        val denied = assertIs<ApprovalClaimResult.Claimed>(
            deniedRepo.consumeApproval(
                deniedApproval.id,
                conversationId,
                ApprovalDecision.DENY,
            ),
        ).invocation
        assertEquals(ToolInvocationStatus.DENIED, denied.status)
        assertIs<ToolResult.Denied>(denied.result)
        assertEquals(
            ApprovalClaimResult.AlreadyConsumed,
            deniedRepo.consumeApproval(
                deniedApproval.id,
                conversationId,
                ApprovalDecision.DENY,
            ),
        )
    }

    @Test
    fun approvalRejectsWrongConversationExpiredAndChangedRequest() = runTest {
        val wrongConversationRepo = ContractRepository(now)
        val approval = pendingApproval()
        wrongConversationRepo.registerToolInvocation(pendingInvocation(approval))
        assertEquals(
            ApprovalClaimResult.WrongConversation,
            wrongConversationRepo.consumeApproval(
                approval.id,
                ConversationId("other"),
                ApprovalDecision.APPROVE,
            ),
        )

        val expiredRepo = ContractRepository(now.plusSeconds(61))
        expiredRepo.registerToolInvocation(pendingInvocation(approval))
        assertEquals(
            ApprovalClaimResult.Expired,
            expiredRepo.consumeApproval(
                approval.id,
                conversationId,
                ApprovalDecision.APPROVE,
            ),
        )
        assertEquals(
            ToolInvocationStatus.EXPIRED,
            expiredRepo.getToolInvocation(conversationId, toolRequest.callId)?.status,
        )

        val changedRequest = request(arguments = "{\"x\":2}")
        assertEquals(
            ApprovalValidationResult.FingerprintMismatch,
            approval.copy(request = changedRequest).validateFor(conversationId, now),
        )
    }

    @Test
    fun concurrentTurnsReceiveUniqueConsecutiveSequences() = runTest {
        val repository = ContractRepository(now)
        val conversation = Conversation(id = conversationId, createdAt = now)
        val turns = (0 until 40).map { index ->
            async(Dispatchers.Default) {
                repository.beginTurn(
                    conversation = conversation,
                    userMessage = draft("user-$index", MessageRole.USER, MessageStatus.COMPLETED),
                    assistantMessage = draft("assistant-$index", MessageRole.ASSISTANT, MessageStatus.SENDING),
                )
            }
        }.awaitAll()

        val assigned = turns.flatMap { listOf(it.userMessage.sequence, it.assistantMessage.sequence) }
        assertEquals(80, assigned.distinct().size)
        assertEquals((0L until 80L).toList(), assigned.sorted())
        assertTrue(turns.all { it.assistantMessage.sequence == it.userMessage.sequence + 1 })
    }

    @Test
    fun staleProviderProgressCannotOverwriteCompletedAssistantMessage() = runTest {
        val repository = ContractRepository(now)
        val conversation = Conversation(id = conversationId, createdAt = now)
        val started = repository.beginTurn(
            conversation,
            draft("user-cas", MessageRole.USER, MessageStatus.COMPLETED),
            draft("assistant-cas", MessageRole.ASSISTANT, MessageStatus.SENDING),
        )
        val completed = started.assistantMessage.copy(
            status = MessageStatus.COMPLETED,
            content = MessageContent.text("final"),
        )
        assertIs<MessageMutationResult.Updated>(
            repository.completeTurn(conversation, completed, MessageStatus.SENDING),
        )

        val staleDelta = started.assistantMessage.copy(
            status = MessageStatus.STREAMING,
            content = MessageContent.text("late delta"),
        )
        val result = repository.updateAssistantProgress(staleDelta, MessageStatus.SENDING)

        assertEquals(MessageMutationResult.Conflict(MessageStatus.COMPLETED), result)
        assertEquals(
            "final",
            repository.getMessages(conversationId).last().content.text,
        )
    }

    @Test
    fun failureAndCancellationArePersistedAsDifferentTerminalOutcomes() = runTest {
        val failedRepository = ContractRepository(now)
        val cancelledRepository = ContractRepository(now)
        val conversation = Conversation(id = conversationId, createdAt = now)
        val failedTurn = failedRepository.beginTurn(
            conversation,
            draft("user-fail", MessageRole.USER, MessageStatus.COMPLETED),
            draft("assistant-fail", MessageRole.ASSISTANT, MessageStatus.SENDING),
        )
        val failed = failedTurn.assistantMessage.copy(
            status = MessageStatus.FAILED,
            error = ConversationError.ProviderFailure(ProviderError.Unavailable),
        )
        assertIs<MessageMutationResult.Updated>(
            failedRepository.failTurn(conversation, failed, MessageStatus.SENDING),
        )

        val cancelledTurn = cancelledRepository.beginTurn(
            conversation,
            draft("user-cancel", MessageRole.USER, MessageStatus.COMPLETED),
            draft("assistant-cancel", MessageRole.ASSISTANT, MessageStatus.SENDING),
        )
        val cancelled = cancelledTurn.assistantMessage.copy(status = MessageStatus.CANCELLED)
        assertIs<MessageMutationResult.Updated>(
            cancelledRepository.cancelTurn(conversation, cancelled, MessageStatus.SENDING),
        )

        val failedStored = failedRepository.getMessages(conversationId).last()
        val cancelledStored = cancelledRepository.getMessages(conversationId).last()
        assertEquals(MessageStatus.FAILED, failedStored.status)
        assertIs<ConversationError.ProviderFailure>(failedStored.error)
        assertEquals(MessageStatus.CANCELLED, cancelledStored.status)
        assertEquals(null, cancelledStored.error)
    }

    @Test
    fun lifecycleDistinguishesFailureCancellationInterruptionAndUnknownToolOutcome() {
        assertTrue(MessageStatus.SENDING.canTransitionTo(MessageStatus.CANCELLED))
        assertTrue(MessageStatus.STREAMING.canTransitionTo(MessageStatus.INTERRUPTED))
        assertFalse(MessageStatus.CANCELLED.canTransitionTo(MessageStatus.COMPLETED))
        assertTrue(ToolInvocationStatus.RUNNING.canTransitionTo(ToolInvocationStatus.CANCELLED))
        assertTrue(ToolInvocationStatus.RUNNING.canTransitionTo(ToolInvocationStatus.UNKNOWN_AFTER_RESTART))
        assertFalse(ToolInvocationStatus.UNKNOWN_AFTER_RESTART.canTransitionTo(ToolInvocationStatus.RUNNING))
    }

    @Test
    fun successfulAndFailedToolOutcomesHaveDistinctTerminalStates() {
        val fingerprint = RequestFingerprint.calculate(conversationId, toolRequest)
        val completed = ToolInvocation(
            conversationId = conversationId,
            request = toolRequest,
            requestFingerprint = fingerprint,
            status = ToolInvocationStatus.COMPLETED,
            result = ToolResult.Success(toolRequest.callId, ToolPayload("{\"ok\":true}")),
        )
        val failed = ToolInvocation(
            conversationId = conversationId,
            request = toolRequest,
            requestFingerprint = fingerprint,
            status = ToolInvocationStatus.FAILED,
            result = ToolResult.Failure(toolRequest.callId, ToolFailureCode.TIMED_OUT),
        )

        assertIs<ToolResult.Success>(completed.result)
        assertEquals(ToolFailureCode.TIMED_OUT, assertIs<ToolResult.Failure>(failed.result).code)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            completed.copy(status = ToolInvocationStatus.FAILED)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ToolInvocation(
                conversationId,
                toolRequest,
                RequestFingerprint.calculate(conversationId, request(arguments = "{\"tampered\":true}")),
                ToolInvocationStatus.APPROVED,
                approval = pendingApproval(),
            )
        }
    }

    @Test
    fun restartRecoverySeparatesInterruptedGenerationFromUnknownToolOutcome() = runTest {
        val repository = ContractRepository(now)
        val conversation = Conversation(id = conversationId, createdAt = now)
        repository.beginTurn(
            conversation,
            draft("user-recovery", MessageRole.USER, MessageStatus.COMPLETED),
            draft("assistant-recovery", MessageRole.ASSISTANT, MessageStatus.SENDING),
        )
        repository.appendMessage(
            draft("system-recovery", MessageRole.SYSTEM, MessageStatus.SENDING),
        )
        val runningRequest = request(callId = "running-call")
        repository.registerToolInvocation(
            ToolInvocation(
                conversationId = conversationId,
                request = runningRequest,
                requestFingerprint = RequestFingerprint.calculate(conversationId, runningRequest),
                status = ToolInvocationStatus.APPROVED,
            ),
        )
        val approvedInvocation = repository.getToolInvocation(conversationId, runningRequest.callId)!!
        repository.transitionToolInvocation(
            conversationId,
            runningRequest.callId,
            ToolInvocationStatus.APPROVED,
            approvedInvocation.copy(status = ToolInvocationStatus.RUNNING),
        )

        val report = repository.recoverInterruptedWork()

        assertEquals(1, report.interruptedMessages)
        assertEquals(1, report.unknownToolOutcomes)
        val recoveredMessages = repository.getMessages(conversationId)
        assertEquals(MessageStatus.INTERRUPTED, recoveredMessages.first { it.role == MessageRole.ASSISTANT }.status)
        assertEquals(MessageStatus.SENDING, recoveredMessages.first { it.role == MessageRole.SYSTEM }.status)
        assertEquals(
            ToolInvocationStatus.UNKNOWN_AFTER_RESTART,
            repository.getToolInvocation(conversationId, runningRequest.callId)?.status,
        )
    }

    @Test
    fun toolLimitsRejectInvalidConfigurationAndUseUtf8ByteCounts() {
        assertEquals(1, ToolPayload("{\"x\":\"é\"}").utf8Size - "{\"x\":\"e\"}".toByteArray().size)
        val limits = ToolLimits()
        assertEquals(64 * 1024, limits.maxResultBytes)
        assertTrue(limits.permitsCall(1, 0, 0))
        assertTrue(!limits.permitsCall(7, 0, 0))
        assertTrue(limits.permitsResult(1024, 0))
        assertTrue(!limits.permitsResult(limits.maxResultBytes + 1, 0))
        kotlin.test.assertFailsWith<IllegalArgumentException> { ToolLimits(maxRounds = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ToolLimits(maxResultBytes = 10, maxTotalResultBytes = 9) }
    }

    private fun request(
        callId: String = "call-1",
        toolId: String = "tool-1",
        arguments: String = "{\"x\":1}",
    ) = ToolRequest(
        callId = ToolCallId(callId),
        toolId = ToolId(toolId),
        arguments = ToolArguments(arguments),
    )

    private fun pendingApproval(
        id: String = "approval-1",
        callId: String = "call-1",
    ) = PendingToolApproval.create(
        id = ApprovalId(id),
        conversationId = conversationId,
        request = request(callId = callId),
        createdAt = now,
        expiresAt = now.plusSeconds(60),
    )

    private fun pendingInvocation(approval: PendingToolApproval) = ToolInvocation(
        conversationId = approval.conversationId,
        request = approval.request,
        requestFingerprint = approval.requestFingerprint,
        status = ToolInvocationStatus.PENDING_APPROVAL,
        approval = approval,
    )

    private fun draft(id: String, role: MessageRole, status: MessageStatus) = MessageDraft(
        id = MessageId(id),
        conversationId = conversationId,
        role = role,
        content = MessageContent.text(id),
        createdAt = now,
        status = status,
    )
}
