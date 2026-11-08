package com.aistudio.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** Canonical JSON object text produced by a provider adapter; core treats it as opaque data. */
data class ToolArguments(val canonicalJson: String) {
    init {
        require(canonicalJson.isNotBlank()) { "Tool arguments must not be blank" }
    }
}

data class ToolPayload(val canonicalJson: String) {
    val utf8Size: Int
        get() = canonicalJson.toByteArray(StandardCharsets.UTF_8).size
}

data class ToolRequest(
    val callId: ToolCallId,
    val toolId: ToolId,
    val arguments: ToolArguments,
)

enum class ToolFailureCode {
    UNKNOWN_TOOL,
    UNAVAILABLE,
    INVALID_ARGUMENTS,
    TIMED_OUT,
    EXECUTION_FAILED,
    OUTPUT_LIMIT_EXCEEDED,
}

sealed interface ToolResult {
    val callId: ToolCallId

    data class Success(
        override val callId: ToolCallId,
        val output: ToolPayload,
    ) : ToolResult

    data class Failure(
        override val callId: ToolCallId,
        val code: ToolFailureCode,
    ) : ToolResult

    data class Denied(override val callId: ToolCallId) : ToolResult
}

enum class ToolInvocationStatus {
    PENDING_APPROVAL,
    APPROVED,
    RUNNING,
    COMPLETED,
    FAILED,
    DENIED,
    EXPIRED,
    CANCELLED,
    UNKNOWN_AFTER_RESTART,
}

enum class ApprovalDecision {
    APPROVE,
    DENY,
}

sealed interface ApprovalValidationResult {
    data class Valid(val approval: PendingToolApproval) : ApprovalValidationResult
    data object WrongConversation : ApprovalValidationResult
    data object Expired : ApprovalValidationResult
    data object FingerprintMismatch : ApprovalValidationResult
}

data class ToolInvocation(
    val conversationId: ConversationId,
    val request: ToolRequest,
    val requestFingerprint: RequestFingerprint,
    val status: ToolInvocationStatus,
    val approval: PendingToolApproval? = null,
    val result: ToolResult? = null,
) {
    init {
        require(requestFingerprint.matches(conversationId, request)) {
            "Invocation fingerprint must match its original request"
        }
        require(approval == null || (
            approval.conversationId == conversationId &&
                approval.request == request &&
                approval.requestFingerprint == requestFingerprint
            )) { "Approval must refer to the invocation's original request" }
        require(result == null || result.callId == request.callId) {
            "Tool result must match the invocation call ID"
        }
        require(status != ToolInvocationStatus.PENDING_APPROVAL || approval != null) {
            "Pending approval status requires an approval record"
        }
        require((status == ToolInvocationStatus.COMPLETED ||
            status == ToolInvocationStatus.FAILED ||
            status == ToolInvocationStatus.DENIED) == (result != null)) {
            "Completed, failed, or denied invocations must carry a result"
        }
        require(status != ToolInvocationStatus.COMPLETED || result is ToolResult.Success)
        require(status != ToolInvocationStatus.FAILED || result is ToolResult.Failure)
        require(status != ToolInvocationStatus.DENIED || result is ToolResult.Denied)
    }
}

fun ToolInvocationStatus.canTransitionTo(next: ToolInvocationStatus): Boolean = when (this) {
    ToolInvocationStatus.PENDING_APPROVAL -> next in setOf(
        ToolInvocationStatus.APPROVED,
        ToolInvocationStatus.DENIED,
        ToolInvocationStatus.EXPIRED,
        ToolInvocationStatus.CANCELLED,
    )
    ToolInvocationStatus.APPROVED -> next in setOf(
        ToolInvocationStatus.RUNNING,
        ToolInvocationStatus.CANCELLED,
        ToolInvocationStatus.EXPIRED,
    )
    ToolInvocationStatus.RUNNING -> next in setOf(
        ToolInvocationStatus.COMPLETED,
        ToolInvocationStatus.FAILED,
        ToolInvocationStatus.CANCELLED,
        ToolInvocationStatus.UNKNOWN_AFTER_RESTART,
    )
    ToolInvocationStatus.COMPLETED,
    ToolInvocationStatus.FAILED,
    ToolInvocationStatus.DENIED,
    ToolInvocationStatus.EXPIRED,
    ToolInvocationStatus.CANCELLED,
    ToolInvocationStatus.UNKNOWN_AFTER_RESTART -> false
}

sealed interface ToolInvocationRegistration {
    data class Created(val invocation: ToolInvocation) : ToolInvocationRegistration
    data class Existing(val invocation: ToolInvocation) : ToolInvocationRegistration
    data object RequestConflict : ToolInvocationRegistration
}

/** Hash of an unambiguous, versioned encoding of the original request fields. */
class RequestFingerprint private constructor(val sha256Hex: String) {
    fun matches(conversationId: ConversationId, request: ToolRequest): Boolean =
        sha256Hex == calculate(conversationId, request).sha256Hex

    override fun equals(other: Any?): Boolean =
        other is RequestFingerprint && sha256Hex == other.sha256Hex

    override fun hashCode(): Int = sha256Hex.hashCode()

    override fun toString(): String = sha256Hex

    companion object {
        /** Computes the fingerprint inside core; callers never submit one during approval resolution. */
        fun calculate(conversationId: ConversationId, request: ToolRequest): RequestFingerprint {
            val canonical = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    writeField(output, "AIStudio.ToolRequest.v1")
                    writeField(output, conversationId.value)
                    writeField(output, request.callId.value)
                    writeField(output, request.toolId.value)
                    writeField(output, request.arguments.canonicalJson)
                }
                bytes.toByteArray()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
            val hex = digest.joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            return RequestFingerprint(hex)
        }

        /** Rehydrates persisted data; the coordinator must still compare it with a fresh calculation. */
        fun fromPersisted(value: String): RequestFingerprint {
            require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid request fingerprint" }
            return RequestFingerprint(value)
        }

        private fun writeField(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }
}

data class PendingToolApproval(
    val id: ApprovalId,
    val conversationId: ConversationId,
    val request: ToolRequest,
    val requestFingerprint: RequestFingerprint,
    val createdAt: Instant,
    val expiresAt: Instant,
    val createdElapsedRealtimeMillis: Long,
    val expiresElapsedRealtimeMillis: Long,
    val bootCount: BootCount.Known,
) {
    init {
        require(expiresAt > createdAt) { "Approval must expire after it is created" }
        require(createdElapsedRealtimeMillis >= 0) { "Approval elapsed creation time must not be negative" }
        require(expiresElapsedRealtimeMillis > createdElapsedRealtimeMillis) {
            "Approval elapsed deadline must follow its creation time"
        }
    }

    fun isBoundToOriginalRequest(): Boolean = requestFingerprint.matches(conversationId, request)

    fun isExpiredAt(now: ClockSnapshot): Boolean = when {
        now.bootCount !is BootCount.Known -> true
        bootCount.value != now.bootCount.value -> true
        now.elapsedRealtimeMillis >= expiresElapsedRealtimeMillis -> true
        else -> false
    }

    fun validateFor(expectedConversationId: ConversationId, now: ClockSnapshot): ApprovalValidationResult = when {
        conversationId != expectedConversationId -> ApprovalValidationResult.WrongConversation
        !isBoundToOriginalRequest() -> ApprovalValidationResult.FingerprintMismatch
        isExpiredAt(now) -> ApprovalValidationResult.Expired
        else -> ApprovalValidationResult.Valid(this)
    }

    companion object {
        fun create(
            id: ApprovalId,
            conversationId: ConversationId,
            request: ToolRequest,
            now: ClockSnapshot,
            ttl: Duration,
        ): PendingToolApproval {
            val approvalBootCount = now.bootCount as? BootCount.Known
                ?: throw IllegalArgumentException("Cannot create a tool approval when the boot count is unknown")
            require(!ttl.isNegative && !ttl.isZero) { "Approval lifetime must be positive" }

            val ttlMillis = try {
                ttl.toMillis()
            } catch (exception: ArithmeticException) {
                throw IllegalArgumentException("Approval lifetime exceeds elapsed-time range", exception)
            }
            require(ttlMillis > 0) { "Approval lifetime must be at least one millisecond" }

            val expiresElapsedRealtimeMillis = try {
                Math.addExact(now.elapsedRealtimeMillis, ttlMillis)
            } catch (exception: ArithmeticException) {
                throw IllegalArgumentException("Approval elapsed deadline overflows", exception)
            }
            val expiresAt = now.wallTime.plus(ttl)

            return PendingToolApproval(
                id = id,
                conversationId = conversationId,
                request = request,
                requestFingerprint = RequestFingerprint.calculate(conversationId, request),
                createdAt = now.wallTime,
                expiresAt = expiresAt,
                createdElapsedRealtimeMillis = now.elapsedRealtimeMillis,
                expiresElapsedRealtimeMillis = expiresElapsedRealtimeMillis,
                bootCount = approvalBootCount,
            )
        }
    }
}

sealed interface ApprovalClaimResult {
    data class Claimed(val invocation: ToolInvocation) : ApprovalClaimResult
    data object NotFound : ApprovalClaimResult
    data object WrongConversation : ApprovalClaimResult
    data object Expired : ApprovalClaimResult
    data object FingerprintMismatch : ApprovalClaimResult
    data object AlreadyConsumed : ApprovalClaimResult
}

sealed interface ToolInvocationTransition {
    data class Updated(val invocation: ToolInvocation) : ToolInvocationTransition
    data class StateConflict(val current: ToolInvocationStatus?) : ToolInvocationTransition
}

sealed interface ToolDispatch {
    data class Completed(val result: ToolResult) : ToolDispatch
    data class PersistenceFailure(val safeMessage: String? = null) : ToolDispatch
    data class AwaitingApproval(
        val approvalId: ApprovalId,
        val conversationId: ConversationId,
        val toolId: ToolId,
        val expiresAt: Instant,
    ) : ToolDispatch
    data class Rejected(val reason: ApprovalFailureCode) : ToolDispatch
}

data class ToolLimits(
    val maxRounds: Int = 6,
    val maxCallsPerRound: Int = 4,
    val maxCallsPerTurn: Int = 12,
    val maxResultBytes: Int = 64 * 1024,
    val maxTotalResultBytes: Int = 256 * 1024,
) {
    init {
        require(maxRounds > 0)
        require(maxCallsPerRound > 0)
        require(maxCallsPerTurn >= maxCallsPerRound)
        require(maxResultBytes > 0)
        require(maxTotalResultBytes >= maxResultBytes)
    }

    fun permitsCall(roundNumber: Int, callsAlreadyInRound: Int, callsAlreadyInTurn: Int): Boolean {
        require(roundNumber > 0 && callsAlreadyInRound >= 0 && callsAlreadyInTurn >= 0)
        return roundNumber <= maxRounds &&
            callsAlreadyInRound < maxCallsPerRound &&
            callsAlreadyInTurn < maxCallsPerTurn
    }

    fun permitsResult(resultBytes: Int, totalBytesBefore: Int): Boolean {
        require(resultBytes >= 0 && totalBytesBefore >= 0)
        return resultBytes <= maxResultBytes &&
            totalBytesBefore <= maxTotalResultBytes &&
            resultBytes <= maxTotalResultBytes - totalBytesBefore
    }
}
