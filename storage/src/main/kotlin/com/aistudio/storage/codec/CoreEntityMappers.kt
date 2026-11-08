package com.aistudio.storage.codec

import com.aistudio.core.model.ApprovalId
import com.aistudio.core.model.BootCount
import com.aistudio.core.model.Conversation
import com.aistudio.core.model.ConversationId
import com.aistudio.core.model.Message
import com.aistudio.core.model.MessageContent
import com.aistudio.core.model.MessageDraft
import com.aistudio.core.model.MessageId
import com.aistudio.core.model.MessageRole
import com.aistudio.core.model.MessageStatus
import com.aistudio.core.model.ModelId
import com.aistudio.core.model.PendingToolApproval
import com.aistudio.core.model.ProviderId
import com.aistudio.core.model.RequestFingerprint
import com.aistudio.core.model.ToolArguments
import com.aistudio.core.model.ToolCallId
import com.aistudio.core.model.ToolId
import com.aistudio.core.model.ToolInvocation
import com.aistudio.core.model.ToolRequest
import com.aistudio.core.model.ToolResult
import com.aistudio.storage.entity.ConversationEntity
import com.aistudio.storage.entity.MessageEntity
import com.aistudio.storage.entity.ToolInvocationEntity
import java.time.Instant

fun Conversation.toEntity(nextSequence: Long = 0): ConversationEntity {
    require(nextSequence >= 0) { "Next message sequence must not be negative" }
    return ConversationEntity(
        id = id.value,
        title = title,
        createdAtEpochSeconds = createdAt.epochSecond,
        createdAtNano = createdAt.nano,
        updatedAtEpochSeconds = updatedAt.epochSecond,
        updatedAtNano = updatedAt.nano,
        providerId = providerId?.value,
        modelId = modelId?.value,
        archivedAtEpochSeconds = archivedAt?.epochSecond,
        archivedAtNano = archivedAt?.nano,
        nextSequence = nextSequence,
    )
}

fun ConversationEntity.toCore(): Conversation {
    require((archivedAtEpochSeconds == null) == (archivedAtNano == null)) {
        "Conversation archive timestamp columns must both be null or present"
    }
    return Conversation(
        id = ConversationId(id),
        title = title,
        createdAt = storedInstant(createdAtEpochSeconds, createdAtNano),
        updatedAt = storedInstant(updatedAtEpochSeconds, updatedAtNano),
        providerId = providerId?.let(::ProviderId),
        modelId = modelId?.let(::ModelId),
        archivedAt = if (archivedAtEpochSeconds == null) null else
            storedInstant(archivedAtEpochSeconds, archivedAtNano!!),
    )
}

fun Message.toEntity(): MessageEntity {
    val toolResult = if (role == MessageRole.TOOL) {
        (content.parts.single() as? com.aistudio.core.model.MessagePart.ToolResult)?.result
            ?: throw IllegalArgumentException("TOOL messages must contain exactly one result")
    } else {
        require(content.parts.none { it is com.aistudio.core.model.MessagePart.ToolResult }) {
            "Only TOOL messages can carry a result link"
        }
        null
    }
    val storedToolResult = toolResult?.let(StorageCodecs::encodeToolResultColumns)
    val errorJson = error?.let(StorageCodecs::encodeConversationError)
    return MessageEntity(
        id = id.value,
        conversationId = conversationId.value,
        sequence = sequence,
        role = StorageCodecs.roleToStorage(role),
        contentFormatVersion = if (toolResult == null) StorageCodecs.CONTENT_VERSION else null,
        contentJson = if (toolResult == null) StorageCodecs.encodeMessageContent(content) else null,
        createdAtEpochSeconds = createdAt.epochSecond,
        createdAtNano = createdAt.nano,
        status = StorageCodecs.messageStatusToStorage(status),
        providerId = providerId?.value,
        modelId = modelId?.value,
        errorFormatVersion = error?.let { StorageCodecs.ERROR_VERSION },
        errorJson = errorJson,
        toolCallId = toolResult?.callId?.value,
        toolResultType = storedToolResult?.type,
        toolResultPayloadJson = storedToolResult?.payloadJson,
        toolFailureCode = storedToolResult?.failureCode,
    )
}

/** Maps a draft after its caller has already selected a sequence; this function allocates none. */
fun MessageDraft.toEntity(sequence: Long): MessageEntity {
    require(sequence >= 0) { "Message sequence must not be negative" }
    val toolResult = if (role == MessageRole.TOOL) {
        (content.parts.single() as? com.aistudio.core.model.MessagePart.ToolResult)?.result
            ?: throw IllegalArgumentException("TOOL messages must contain exactly one result")
    } else {
        require(content.parts.none { it is com.aistudio.core.model.MessagePart.ToolResult }) {
            "Only TOOL messages can carry a result link"
        }
        null
    }
    val storedToolResult = toolResult?.let(StorageCodecs::encodeToolResultColumns)
    return MessageEntity(
        id = id.value,
        conversationId = conversationId.value,
        sequence = sequence,
        role = StorageCodecs.roleToStorage(role),
        contentFormatVersion = if (toolResult == null) StorageCodecs.CONTENT_VERSION else null,
        contentJson = if (toolResult == null) StorageCodecs.encodeMessageContent(content) else null,
        createdAtEpochSeconds = createdAt.epochSecond,
        createdAtNano = createdAt.nano,
        status = StorageCodecs.messageStatusToStorage(status),
        providerId = null,
        modelId = null,
        errorFormatVersion = null,
        errorJson = null,
        toolCallId = toolResult?.callId?.value,
        toolResultType = storedToolResult?.type,
        toolResultPayloadJson = storedToolResult?.payloadJson,
        toolFailureCode = storedToolResult?.failureCode,
    )
}

fun MessageEntity.toCore(): Message {
    require((errorFormatVersion == null) == (errorJson == null)) {
        "Message error version and payload must both be null or present"
    }
    val decodedRole = StorageCodecs.roleFromStorage(role)
    val decodedContent = decodeContent(decodedRole)
    return Message(
        id = MessageId(id),
        conversationId = ConversationId(conversationId),
        role = decodedRole,
        content = decodedContent,
        createdAt = storedInstant(createdAtEpochSeconds, createdAtNano),
        sequence = sequence,
        status = StorageCodecs.messageStatusFromStorage(status),
        providerId = providerId?.let(::ProviderId),
        modelId = modelId?.let(::ModelId),
        error = if (errorJson == null) null else
            StorageCodecs.decodeConversationError(errorFormatVersion!!, errorJson),
    )
}

/** Restores rows represented by Core only as drafts without weakening Message validation. */
fun MessageEntity.toRestoredMessage(): RestoredMessage {
    require((errorFormatVersion == null) == (errorJson == null)) {
        "Message error version and payload must both be null or present"
    }
    val decodedStatus = StorageCodecs.messageStatusFromStorage(status)
    if ((decodedStatus == MessageStatus.FAILED || decodedStatus == MessageStatus.INTERRUPTED) && errorJson == null
    ) {
        val decodedRole = StorageCodecs.roleFromStorage(role)
        return RestoredMessage.Draft(
            draft = MessageDraft(
                id = MessageId(id),
                conversationId = ConversationId(conversationId),
                role = decodedRole,
                content = decodeContent(decodedRole),
                createdAt = storedInstant(createdAtEpochSeconds, createdAtNano),
                status = decodedStatus,
            ),
            sequence = sequence,
            providerId = providerId?.let(::ProviderId),
            modelId = modelId?.let(::ModelId),
        )
    }
    return RestoredMessage.Persisted(toCore())
}

private fun MessageEntity.decodeContent(decodedRole: MessageRole): MessageContent = if (decodedRole == MessageRole.TOOL) {
    require(contentFormatVersion == null && contentJson == null) {
        "TOOL messages must use the dedicated result columns"
    }
    val callId = ToolCallId(requireNotNull(toolCallId) { "TOOL message call ID is required" })
    val resultType = requireNotNull(toolResultType) { "TOOL message result type is required" }
    MessageContent(
        listOf(
            com.aistudio.core.model.MessagePart.ToolResult(
                StorageCodecs.decodeToolResultColumns(
                    callId,
                    resultType,
                    toolResultPayloadJson,
                    toolFailureCode,
                ),
            ),
        ),
    )
} else {
    require(toolCallId == null && toolResultType == null &&
        toolResultPayloadJson == null && toolFailureCode == null
    ) { "Only TOOL messages may carry result columns" }
    StorageCodecs.decodeMessageContent(
        requireNotNull(contentFormatVersion) { "Message content version is required" },
        requireNotNull(contentJson) { "Message content is required" },
    )
}

fun ToolInvocation.toEntity(): ToolInvocationEntity {
    val approval = approval
    val resultJson = result?.let(StorageCodecs::encodeToolResult)
    return ToolInvocationEntity(
        conversationId = conversationId.value,
        toolCallId = request.callId.value,
        toolId = request.toolId.value,
        argumentsJson = request.arguments.canonicalJson,
        requestFingerprint = requestFingerprint.sha256Hex,
        fingerprintVersion = StorageCodecs.FINGERPRINT_VERSION,
        status = StorageCodecs.invocationStatusToStorage(status),
        resultFormatVersion = result?.let { StorageCodecs.RESULT_VERSION },
        resultJson = resultJson,
        approvalId = approval?.id?.value,
        approvalCreatedAtEpochSeconds = approval?.createdAt?.epochSecond,
        approvalCreatedAtNano = approval?.createdAt?.nano,
        approvalExpiresAtEpochSeconds = approval?.expiresAt?.epochSecond,
        approvalExpiresAtNano = approval?.expiresAt?.nano,
        approvalCreatedElapsedRealtimeMillis = approval?.createdElapsedRealtimeMillis,
        approvalExpiresElapsedRealtimeMillis = approval?.expiresElapsedRealtimeMillis,
        approvalBootCount = approval?.bootCount?.value,
    )
}

fun ToolInvocationEntity.toCore(): ToolInvocation {
    require((resultFormatVersion == null) == (resultJson == null)) {
        "Tool result version and payload must both be null or present"
    }
    val approvalColumns = listOf(
        approvalId,
        approvalCreatedAtEpochSeconds,
        approvalCreatedAtNano,
        approvalExpiresAtEpochSeconds,
        approvalExpiresAtNano,
        approvalCreatedElapsedRealtimeMillis,
        approvalExpiresElapsedRealtimeMillis,
        approvalBootCount,
    )
    require(approvalColumns.all { it == null } || approvalColumns.all { it != null }) {
        "Approval columns must all be null or all be present"
    }
    if (fingerprintVersion != StorageCodecs.FINGERPRINT_VERSION) {
        throw UnsupportedStoredDataException("request fingerprint version", fingerprintVersion.toString())
    }
    val callId = ToolCallId(toolCallId)
    val toolRequest = ToolRequest(callId, ToolId(toolId), ToolArguments(argumentsJson))
    val conversationKey = ConversationId(conversationId)
    val approval = if (approvalId == null) null else PendingToolApproval(
        id = ApprovalId(approvalId),
        conversationId = conversationKey,
        request = toolRequest,
        requestFingerprint = RequestFingerprint.fromPersisted(requestFingerprint),
        createdAt = storedInstant(approvalCreatedAtEpochSeconds!!, approvalCreatedAtNano!!),
        expiresAt = storedInstant(approvalExpiresAtEpochSeconds!!, approvalExpiresAtNano!!),
        createdElapsedRealtimeMillis = approvalCreatedElapsedRealtimeMillis!!,
        expiresElapsedRealtimeMillis = approvalExpiresElapsedRealtimeMillis!!,
        bootCount = BootCount.Known(approvalBootCount!!),
    )
    return ToolInvocation(
        conversationId = conversationKey,
        request = toolRequest,
        requestFingerprint = RequestFingerprint.fromPersisted(requestFingerprint),
        status = StorageCodecs.invocationStatusFromStorage(status),
        approval = approval,
        result = if (resultJson == null) null else
            StorageCodecs.decodeToolResult(resultFormatVersion!!, resultJson),
    )
}

private fun storedInstant(epochSeconds: Long, nanoAdjustment: Int): Instant {
    require(nanoAdjustment in 0..999_999_999) { "Stored nanosecond adjustment is out of range" }
    return Instant.ofEpochSecond(epochSeconds, nanoAdjustment.toLong())
}
