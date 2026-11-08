package com.aistudio.core.contract

import com.aistudio.core.model.ApprovalDecision
import com.aistudio.core.model.ApprovalId
import com.aistudio.core.model.ConversationId
import com.aistudio.core.model.ToolDispatch
import com.aistudio.core.model.ToolRequest

/**
 * Coordinates generic tool execution and approval without exposing MCP or permission implementations.
 * Execution is sequential in the first engine version; call IDs are idempotency keys.
 */
interface ToolCoordinator {
    /**
     * Registers the original call before execution. Duplicate call IDs return the stored state/result;
     * a call ID reused with different canonical arguments is rejected.
     */
    suspend fun execute(conversationId: ConversationId, request: ToolRequest): ToolDispatch

    /**
     * Accepts no request or fingerprint from the UI. The coordinator loads the bound original call,
     * recomputes its fingerprint, validates the conversation, then asks the repository to atomically
     * consume the pending approval. The repository independently verifies the stored fingerprint
     * and expiry using its trusted clock.
     */
    suspend fun resolveApproval(
        conversationId: ConversationId,
        approvalId: ApprovalId,
        decision: ApprovalDecision,
    ): ToolDispatch
}
