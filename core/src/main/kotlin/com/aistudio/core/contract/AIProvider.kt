package com.aistudio.core.contract

import com.aistudio.core.model.ProviderCallResult
import com.aistudio.core.model.ProviderCapabilities
import com.aistudio.core.model.ProviderId
import com.aistudio.core.model.ProviderRequest
import com.aistudio.core.model.ProviderUpdate

/** Provider adapter contract. Implementations translate between core models and provider-specific APIs. */
interface AIProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: ProviderCapabilities

    /**
     * Emits optional text deltas and returns a normalized answer or tool-call turn. The engine
     * executes tool calls and invokes this method again with the updated ordered transcript.
     * Implementations must propagate coroutine cancellation.
     */
    suspend fun generate(
        request: ProviderRequest,
        onUpdate: suspend (ProviderUpdate) -> Unit = {},
    ): ProviderCallResult
}
