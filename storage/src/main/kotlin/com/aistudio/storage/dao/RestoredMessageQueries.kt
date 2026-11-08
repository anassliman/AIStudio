package com.aistudio.storage.dao

import com.aistudio.storage.codec.RestoredMessage
import com.aistudio.storage.codec.toRestoredMessage

/** Reads a row into the Core type it can represent without weakening Message validation. */
suspend fun MessageDao.getRestoredById(id: String): RestoredMessage? =
    getById(id)?.toRestoredMessage()

/** Reads conversation rows as valid Core messages or drafts, preserving their sequence metadata. */
suspend fun MessageDao.listRestoredForConversation(conversationId: String): List<RestoredMessage> =
    listForConversation(conversationId).map { it.toRestoredMessage() }
