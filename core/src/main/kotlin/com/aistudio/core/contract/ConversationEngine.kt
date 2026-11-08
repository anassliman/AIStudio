package com.aistudio.core.contract

import com.aistudio.core.model.ConversationResult
import com.aistudio.core.model.ApprovalDecision
import com.aistudio.core.model.ApprovalId
import com.aistudio.core.model.ConversationId
import com.aistudio.core.model.SendMessageRequest
import com.aistudio.core.model.ToolLimits
import com.aistudio.core.model.ToolCallId

/** Coordinates a conversation without depending on UI, storage, or provider implementations. */
interface ConversationEngine {
    /** Limits are fixed by the engine/application configuration, never supplied per request. */
    val toolLimits: ToolLimits

    /**
     * Implementations serialize sends per conversation, execute calls sequentially, enforce round,
     * call and UTF-8 result-size limits, and persist the updated transcript before the next provider call.
     */
    suspend fun send(request: SendMessageRequest): ConversationResult

    /** Consumes the bound approval once, then resumes the same conversation/tool loop. */
    suspend fun resolveApproval(
        conversationId: ConversationId,
        approvalId: ApprovalId,
        decision: ApprovalDecision,
    ): ConversationResult

    /** Explicitly resumes a previously approved tool call after validating its persisted approval. */
    suspend fun resumeApprovedTool(
        conversationId: ConversationId,
        callId: ToolCallId,
    ): ConversationResult
}
