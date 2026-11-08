package com.aistudio.storage.codec

import com.aistudio.core.model.Message
import com.aistudio.core.model.MessageDraft
import com.aistudio.core.model.ModelId
import com.aistudio.core.model.ProviderId

/** A valid Core message, or a draft plus storage metadata omitted by Core's draft contract. */
sealed interface RestoredMessage {
    data class Persisted(val message: Message) : RestoredMessage

    data class Draft(
        val draft: MessageDraft,
        val sequence: Long,
        val providerId: ProviderId?,
        val modelId: ModelId?,
    ) : RestoredMessage
}
