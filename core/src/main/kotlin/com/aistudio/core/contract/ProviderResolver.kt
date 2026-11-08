package com.aistudio.core.contract

import com.aistudio.core.model.ModelId
import com.aistudio.core.model.ProviderId
import com.aistudio.core.model.ProviderSelection

/** Resolves locally registered/configured providers and validates an effective model choice. */
interface ProviderResolver {
    fun resolve(selection: ProviderSelection): ProviderResolution
}

sealed interface ProviderResolution {
    data class Ready(val provider: AIProvider, val modelId: ModelId) : ProviderResolution
    data class NotRegistered(val providerId: ProviderId) : ProviderResolution
    data class Unavailable(val providerId: ProviderId, val safeReason: String) : ProviderResolution
    data class ModelUnavailable(val providerId: ProviderId, val modelId: ModelId) : ProviderResolution
    data class ModelRequired(val providerId: ProviderId) : ProviderResolution
}
