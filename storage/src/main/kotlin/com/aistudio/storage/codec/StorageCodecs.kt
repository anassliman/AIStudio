package com.aistudio.storage.codec

import com.aistudio.core.model.ApprovalFailureCode
import com.aistudio.core.model.ConversationError
import com.aistudio.core.model.MessageContent
import com.aistudio.core.model.MessagePart
import com.aistudio.core.model.ProviderError
import com.aistudio.core.model.ToolCallId
import com.aistudio.core.model.ToolFailureCode
import com.aistudio.core.model.ToolId
import com.aistudio.core.model.ToolPayload
import com.aistudio.core.model.ToolRequest
import com.aistudio.core.model.ToolArguments
import com.aistudio.core.model.ToolResult
import com.aistudio.core.model.ConversationId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Explicit, versioned persistence codecs. Tags are stable data values, never Kotlin class names. */
object StorageCodecs {
    const val CONTENT_VERSION = 1
    const val ERROR_VERSION = 1
    const val RESULT_VERSION = 1
    const val FINGERPRINT_VERSION = 1

    private val json = Json {}

    fun encodeMessageContent(content: MessageContent): String = buildJsonObject {
        put("parts", buildJsonArray {
            content.parts.forEach { part ->
                add(when (part) {
                    is MessagePart.Text -> buildJsonObject {
                        put("type", "text")
                        put("value", part.value)
                    }
                    is MessagePart.ToolCall -> buildJsonObject {
                        put("type", "tool_call")
                        put("call_id", part.request.callId.value)
                        put("tool_id", part.request.toolId.value)
                        // Keep the canonical source text intact for request fingerprint stability.
                        put("arguments_json", part.request.arguments.canonicalJson)
                    }
                    is MessagePart.ToolResult -> throw IllegalArgumentException(
                        "Tool results are stored in the dedicated result columns, not contentJson",
                    )
                })
            }
        })
    }.toString()

    fun decodeMessageContent(version: Int, encoded: String): MessageContent {
        requireVersion("message content", version, CONTENT_VERSION)
        val root = json.parseToJsonElement(encoded).asObject("message content")
        val parts = root.required("parts").asArray("parts").map { item ->
            val part = item.asObject("message part")
            when (val type = part.requiredString("type")) {
                "text" -> MessagePart.Text(part.requiredString("value"))
                "tool_call" -> MessagePart.ToolCall(
                    ToolRequest(
                        callId = ToolCallId(part.requiredString("call_id")),
                        toolId = ToolId(part.requiredString("tool_id")),
                        arguments = ToolArguments(part.requiredString("arguments_json")),
                    ),
                )
                else -> throw UnsupportedStoredDataException("message part type", type)
            }
        }
        return MessageContent(parts)
    }

    fun encodeToolResult(result: ToolResult): String = encodeResultObject(result).toString()

    fun encodeToolResultColumns(result: ToolResult): StoredToolResult = when (result) {
        is ToolResult.Success -> StoredToolResult("success", result.output.canonicalJson, null)
        is ToolResult.Failure -> StoredToolResult("failure", null, failureCodeToStorage(result.code))
        is ToolResult.Denied -> StoredToolResult("denied", null, null)
    }

    fun decodeToolResultColumns(
        callId: ToolCallId,
        type: String,
        payloadJson: String?,
        failureCode: String?,
    ): ToolResult = when (type) {
        "success" -> {
            require(payloadJson != null && failureCode == null) { "Malformed successful tool result columns" }
            ToolResult.Success(callId, ToolPayload(payloadJson))
        }
        "failure" -> {
            require(payloadJson == null && failureCode != null) { "Malformed failed tool result columns" }
            ToolResult.Failure(callId, failureCodeFromStorage(failureCode))
        }
        "denied" -> {
            require(payloadJson == null && failureCode == null) { "Malformed denied tool result columns" }
            ToolResult.Denied(callId)
        }
        else -> throw UnsupportedStoredDataException("tool result type", type)
    }

    fun decodeToolResult(version: Int, encoded: String): ToolResult {
        requireVersion("tool result", version, RESULT_VERSION)
        return decodeResultObject(json.parseToJsonElement(encoded).asObject("tool result"))
    }

    fun encodeConversationError(error: ConversationError): String = encodeErrorObject(error).toString()

    fun decodeConversationError(version: Int, encoded: String): ConversationError {
        requireVersion("conversation error", version, ERROR_VERSION)
        return decodeErrorObject(json.parseToJsonElement(encoded).asObject("conversation error"))
    }

    fun roleToStorage(value: com.aistudio.core.model.MessageRole): String = when (value) {
        com.aistudio.core.model.MessageRole.SYSTEM -> "system"
        com.aistudio.core.model.MessageRole.USER -> "user"
        com.aistudio.core.model.MessageRole.ASSISTANT -> "assistant"
        com.aistudio.core.model.MessageRole.TOOL -> "tool"
    }

    fun roleFromStorage(value: String): com.aistudio.core.model.MessageRole = when (value) {
        "system" -> com.aistudio.core.model.MessageRole.SYSTEM
        "user" -> com.aistudio.core.model.MessageRole.USER
        "assistant" -> com.aistudio.core.model.MessageRole.ASSISTANT
        "tool" -> com.aistudio.core.model.MessageRole.TOOL
        else -> throw UnsupportedStoredDataException("message role", value)
    }

    fun messageStatusToStorage(value: com.aistudio.core.model.MessageStatus): String = when (value) {
        com.aistudio.core.model.MessageStatus.PENDING -> "pending"
        com.aistudio.core.model.MessageStatus.SENDING -> "sending"
        com.aistudio.core.model.MessageStatus.STREAMING -> "streaming"
        com.aistudio.core.model.MessageStatus.COMPLETED -> "completed"
        com.aistudio.core.model.MessageStatus.FAILED -> "failed"
        com.aistudio.core.model.MessageStatus.CANCELLED -> "cancelled"
        com.aistudio.core.model.MessageStatus.INTERRUPTED -> "interrupted"
    }

    fun messageStatusFromStorage(value: String): com.aistudio.core.model.MessageStatus = when (value) {
        "pending" -> com.aistudio.core.model.MessageStatus.PENDING
        "sending" -> com.aistudio.core.model.MessageStatus.SENDING
        "streaming" -> com.aistudio.core.model.MessageStatus.STREAMING
        "completed" -> com.aistudio.core.model.MessageStatus.COMPLETED
        "failed" -> com.aistudio.core.model.MessageStatus.FAILED
        "cancelled" -> com.aistudio.core.model.MessageStatus.CANCELLED
        "interrupted" -> com.aistudio.core.model.MessageStatus.INTERRUPTED
        else -> throw UnsupportedStoredDataException("message status", value)
    }

    fun invocationStatusToStorage(value: com.aistudio.core.model.ToolInvocationStatus): String = when (value) {
        com.aistudio.core.model.ToolInvocationStatus.PENDING_APPROVAL -> "pending_approval"
        com.aistudio.core.model.ToolInvocationStatus.APPROVED -> "approved"
        com.aistudio.core.model.ToolInvocationStatus.RUNNING -> "running"
        com.aistudio.core.model.ToolInvocationStatus.COMPLETED -> "completed"
        com.aistudio.core.model.ToolInvocationStatus.FAILED -> "failed"
        com.aistudio.core.model.ToolInvocationStatus.DENIED -> "denied"
        com.aistudio.core.model.ToolInvocationStatus.EXPIRED -> "expired"
        com.aistudio.core.model.ToolInvocationStatus.CANCELLED -> "cancelled"
        com.aistudio.core.model.ToolInvocationStatus.UNKNOWN_AFTER_RESTART -> "unknown_after_restart"
    }

    fun invocationStatusFromStorage(value: String): com.aistudio.core.model.ToolInvocationStatus = when (value) {
        "pending_approval" -> com.aistudio.core.model.ToolInvocationStatus.PENDING_APPROVAL
        "approved" -> com.aistudio.core.model.ToolInvocationStatus.APPROVED
        "running" -> com.aistudio.core.model.ToolInvocationStatus.RUNNING
        "completed" -> com.aistudio.core.model.ToolInvocationStatus.COMPLETED
        "failed" -> com.aistudio.core.model.ToolInvocationStatus.FAILED
        "denied" -> com.aistudio.core.model.ToolInvocationStatus.DENIED
        "expired" -> com.aistudio.core.model.ToolInvocationStatus.EXPIRED
        "cancelled" -> com.aistudio.core.model.ToolInvocationStatus.CANCELLED
        "unknown_after_restart" -> com.aistudio.core.model.ToolInvocationStatus.UNKNOWN_AFTER_RESTART
        else -> throw UnsupportedStoredDataException("tool invocation status", value)
    }

    private fun encodeResultObject(result: ToolResult): JsonObject = buildJsonObject {
        put("call_id", result.callId.value)
        when (result) {
            is ToolResult.Success -> {
                put("type", "success")
                put("output_json", result.output.canonicalJson)
            }
            is ToolResult.Failure -> {
                put("type", "failure")
                put("code", failureCodeToStorage(result.code))
            }
            is ToolResult.Denied -> put("type", "denied")
        }
    }

    private fun decodeResultObject(value: JsonObject): ToolResult {
        val callId = ToolCallId(value.requiredString("call_id"))
        return when (val type = value.requiredString("type")) {
            "success" -> ToolResult.Success(callId, ToolPayload(value.requiredString("output_json")))
            "failure" -> ToolResult.Failure(callId, failureCodeFromStorage(value.requiredString("code")))
            "denied" -> ToolResult.Denied(callId)
            else -> throw UnsupportedStoredDataException("tool result type", type)
        }
    }

    private fun encodeErrorObject(error: ConversationError): JsonObject = buildJsonObject {
        when (error) {
            is ConversationError.InvalidRequest -> { put("type", "invalid_request"); put("reason", error.reason) }
            is ConversationError.ConversationNotFound -> {
                put("type", "conversation_not_found"); put("conversation_id", error.conversationId.value)
            }
            is ConversationError.ProviderNotFound -> { put("type", "provider_not_found"); put("provider_id", error.providerId.value) }
            is ConversationError.ProviderUnavailable -> {
                put("type", "provider_unavailable"); put("provider_id", error.providerId.value); put("reason", error.reason)
            }
            is ConversationError.ModelUnavailable -> {
                put("type", "model_unavailable"); put("provider_id", error.providerId.value); put("model_id", error.modelId.value)
            }
            is ConversationError.ProviderFailure -> {
                put("type", "provider_failure"); put("provider_error", encodeProviderError(error.error))
            }
            is ConversationError.StorageFailure -> {
                put("type", "storage_failure"); putNullable("safe_message", error.safeMessage)
            }
            ConversationError.ToolRoundLimitExceeded -> put("type", "tool_round_limit_exceeded")
            ConversationError.ToolCallLimitExceeded -> put("type", "tool_call_limit_exceeded")
            ConversationError.ProcessInterrupted -> put("type", "process_interrupted")
            is ConversationError.ApprovalRejected -> {
                put("type", "approval_rejected"); put("reason", approvalFailureToStorage(error.reason))
            }
        }
    }

    private fun decodeErrorObject(value: JsonObject): ConversationError = when (val type = value.requiredString("type")) {
        "invalid_request" -> ConversationError.InvalidRequest(value.requiredString("reason"))
        "conversation_not_found" -> ConversationError.ConversationNotFound(ConversationId(value.requiredString("conversation_id")))
        "provider_not_found" -> ConversationError.ProviderNotFound(com.aistudio.core.model.ProviderId(value.requiredString("provider_id")))
        "provider_unavailable" -> ConversationError.ProviderUnavailable(
            com.aistudio.core.model.ProviderId(value.requiredString("provider_id")), value.requiredString("reason"),
        )
        "model_unavailable" -> ConversationError.ModelUnavailable(
            com.aistudio.core.model.ProviderId(value.requiredString("provider_id")),
            com.aistudio.core.model.ModelId(value.requiredString("model_id")),
        )
        "provider_failure" -> ConversationError.ProviderFailure(decodeProviderError(value.required("provider_error").asObject("provider_error")))
        "storage_failure" -> ConversationError.StorageFailure(value.nullableString("safe_message"))
        "tool_round_limit_exceeded" -> ConversationError.ToolRoundLimitExceeded
        "tool_call_limit_exceeded" -> ConversationError.ToolCallLimitExceeded
        "process_interrupted" -> ConversationError.ProcessInterrupted
        "approval_rejected" -> ConversationError.ApprovalRejected(approvalFailureFromStorage(value.requiredString("reason")))
        else -> throw UnsupportedStoredDataException("conversation error type", type)
    }

    private fun encodeProviderError(error: ProviderError): JsonObject = buildJsonObject {
        when (error) {
            ProviderError.Unavailable -> put("type", "unavailable")
            ProviderError.AuthenticationRequired -> put("type", "authentication_required")
            is ProviderError.RateLimited -> {
                put("type", "rate_limited")
                putNullable("retry_after_seconds", error.retryAfterSeconds)
            }
            is ProviderError.Rejected -> { put("type", "rejected"); put("reason", error.reason) }
            is ProviderError.Unknown -> { put("type", "unknown"); putNullable("safe_message", error.safeMessage) }
        }
    }

    private fun decodeProviderError(value: JsonObject): ProviderError = when (val type = value.requiredString("type")) {
        "unavailable" -> ProviderError.Unavailable
        "authentication_required" -> ProviderError.AuthenticationRequired
        "rate_limited" -> ProviderError.RateLimited(value.nullableLong("retry_after_seconds"))
        "rejected" -> ProviderError.Rejected(value.requiredString("reason"))
        "unknown" -> ProviderError.Unknown(value.nullableString("safe_message"))
        else -> throw UnsupportedStoredDataException("provider error type", type)
    }

    private fun failureCodeToStorage(value: ToolFailureCode): String = when (value) {
        ToolFailureCode.UNKNOWN_TOOL -> "unknown_tool"
        ToolFailureCode.UNAVAILABLE -> "unavailable"
        ToolFailureCode.INVALID_ARGUMENTS -> "invalid_arguments"
        ToolFailureCode.TIMED_OUT -> "timed_out"
        ToolFailureCode.EXECUTION_FAILED -> "execution_failed"
        ToolFailureCode.OUTPUT_LIMIT_EXCEEDED -> "output_limit_exceeded"
    }

    private fun failureCodeFromStorage(value: String): ToolFailureCode = when (value) {
        "unknown_tool" -> ToolFailureCode.UNKNOWN_TOOL
        "unavailable" -> ToolFailureCode.UNAVAILABLE
        "invalid_arguments" -> ToolFailureCode.INVALID_ARGUMENTS
        "timed_out" -> ToolFailureCode.TIMED_OUT
        "execution_failed" -> ToolFailureCode.EXECUTION_FAILED
        "output_limit_exceeded" -> ToolFailureCode.OUTPUT_LIMIT_EXCEEDED
        else -> throw UnsupportedStoredDataException("tool failure code", value)
    }

    private fun approvalFailureToStorage(value: ApprovalFailureCode): String = when (value) {
        ApprovalFailureCode.NOT_FOUND -> "not_found"
        ApprovalFailureCode.WRONG_CONVERSATION -> "wrong_conversation"
        ApprovalFailureCode.EXPIRED -> "expired"
        ApprovalFailureCode.FINGERPRINT_MISMATCH -> "fingerprint_mismatch"
        ApprovalFailureCode.ALREADY_CONSUMED -> "already_consumed"
    }

    private fun approvalFailureFromStorage(value: String): ApprovalFailureCode = when (value) {
        "not_found" -> ApprovalFailureCode.NOT_FOUND
        "wrong_conversation" -> ApprovalFailureCode.WRONG_CONVERSATION
        "expired" -> ApprovalFailureCode.EXPIRED
        "fingerprint_mismatch" -> ApprovalFailureCode.FINGERPRINT_MISMATCH
        "already_consumed" -> ApprovalFailureCode.ALREADY_CONSUMED
        else -> throw UnsupportedStoredDataException("approval failure code", value)
    }

    private fun requireVersion(kind: String, actual: Int, supported: Int) {
        if (actual != supported) throw UnsupportedStoredDataException(kind, actual.toString())
    }
}

data class StoredToolResult(
    val type: String,
    val payloadJson: String?,
    val failureCode: String?,
)

class UnsupportedStoredDataException(kind: String, value: String) :
    IllegalArgumentException("Unsupported stored $kind: $value")

private fun JsonElement.asObject(label: String): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("Stored $label must be a JSON object")

private fun JsonElement.asArray(label: String): JsonArray =
    this as? JsonArray ?: throw IllegalArgumentException("Stored $label must be a JSON array")

private fun JsonObject.required(key: String): JsonElement =
    this[key]?.takeUnless { it is JsonNull }
        ?: throw IllegalArgumentException("Stored JSON is missing '$key'")

private fun JsonObject.requiredString(key: String): String {
    val primitive = required(key) as? JsonPrimitive
        ?: throw IllegalArgumentException("Stored '$key' must be a string")
    if (!primitive.isString) throw IllegalArgumentException("Stored '$key' must be a string")
    return primitive.content
}

private fun JsonObject.nullableString(key: String): String? {
    val value = this[key] ?: throw IllegalArgumentException("Stored JSON is missing '$key'")
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("Stored '$key' must be a string or null")
    if (!primitive.isString) throw IllegalArgumentException("Stored '$key' must be a string or null")
    return primitive.content
}

private fun JsonObject.nullableLong(key: String): Long? {
    val value = this[key] ?: throw IllegalArgumentException("Stored JSON is missing '$key'")
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("Stored '$key' must be an integer or null")
    return primitive.longOrNull ?: throw IllegalArgumentException("Stored '$key' must be an integer or null")
}

private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}
