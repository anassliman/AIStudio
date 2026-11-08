package com.aistudio.storage

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Installs SQLite-level row checks Room's Entity annotations cannot express. */
object StorageDatabaseIntegrity {
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) = install(connection)

        // Also installs guards for databases upgraded by a later explicit Room migration.
        override suspend fun onOpen(connection: SQLiteConnection) = install(connection)

        override suspend fun onDestructiveMigration(connection: SQLiteConnection) = install(connection)
    }

    private suspend fun install(connection: SQLiteConnection) {
        guards.forEach { guard -> connection.execSQL(guard.sql()) }
    }

    private data class Guard(
        val table: String,
        val operation: String,
        val invalidWhen: String,
    ) {
        fun sql(): String = """
            CREATE TRIGGER IF NOT EXISTS storage_c_v1_${table}_${operation.lowercase()}
            BEFORE $operation ON $table
            FOR EACH ROW WHEN ($invalidWhen)
            BEGIN
                SELECT RAISE(ABORT, 'Invalid $table row');
            END
        """.trimIndent()
    }

    private val conversationInvalid = """
        length(trim(NEW.id)) = 0
        OR (NEW.title IS NOT NULL AND length(trim(NEW.title)) = 0)
        OR NEW.createdAtNano NOT BETWEEN 0 AND 999999999
        OR NEW.updatedAtNano NOT BETWEEN 0 AND 999999999
        OR NEW.nextSequence < 0
        OR (NEW.providerId IS NOT NULL AND length(trim(NEW.providerId)) = 0)
        OR (NEW.modelId IS NOT NULL AND length(trim(NEW.modelId)) = 0)
        OR (NEW.modelId IS NOT NULL AND NEW.providerId IS NULL)
        OR NEW.updatedAtEpochSeconds < NEW.createdAtEpochSeconds
        OR (NEW.updatedAtEpochSeconds = NEW.createdAtEpochSeconds
            AND NEW.updatedAtNano < NEW.createdAtNano)
        OR ((NEW.archivedAtEpochSeconds IS NULL) != (NEW.archivedAtNano IS NULL))
        OR (NEW.archivedAtEpochSeconds IS NOT NULL AND (
            NEW.archivedAtNano NOT BETWEEN 0 AND 999999999
            OR NEW.archivedAtEpochSeconds < NEW.createdAtEpochSeconds
            OR (NEW.archivedAtEpochSeconds = NEW.createdAtEpochSeconds
                AND NEW.archivedAtNano < NEW.createdAtNano)
        ))
    """.trimIndent()

    private val messageInvalid = """
        length(trim(NEW.id)) = 0
        OR length(trim(NEW.conversationId)) = 0
        OR NEW.sequence < 0
        OR NEW.createdAtNano NOT BETWEEN 0 AND 999999999
        OR NEW.role NOT IN ('system', 'user', 'assistant', 'tool')
        OR NEW.status NOT IN ('pending', 'sending', 'streaming', 'completed', 'failed', 'cancelled', 'interrupted')
        OR (NEW.providerId IS NOT NULL AND length(trim(NEW.providerId)) = 0)
        OR (NEW.modelId IS NOT NULL AND length(trim(NEW.modelId)) = 0)
        OR ((NEW.errorFormatVersion IS NULL) != (NEW.errorJson IS NULL))
        OR (NEW.errorFormatVersion IS NOT NULL AND NEW.errorFormatVersion <= 0)
        OR (NEW.errorJson IS NOT NULL AND NEW.status NOT IN ('failed', 'interrupted'))
        OR (
            NEW.role = 'tool' AND (
                NEW.toolCallId IS NULL OR length(trim(NEW.toolCallId)) = 0
                OR NEW.toolResultType IS NULL
                OR NEW.toolResultType NOT IN ('success', 'failure', 'denied')
                OR NEW.contentFormatVersion IS NOT NULL OR NEW.contentJson IS NOT NULL
                OR (NEW.toolResultType = 'success' AND (
                    NEW.toolResultPayloadJson IS NULL OR NEW.toolFailureCode IS NOT NULL
                ))
                OR (NEW.toolResultType = 'failure' AND (
                    NEW.toolResultPayloadJson IS NOT NULL
                    OR NEW.toolFailureCode IS NULL
                    OR NEW.toolFailureCode NOT IN (
                        'unknown_tool', 'unavailable', 'invalid_arguments', 'timed_out',
                        'execution_failed', 'output_limit_exceeded'
                    )
                ))
                OR (NEW.toolResultType = 'denied' AND (
                    NEW.toolResultPayloadJson IS NOT NULL OR NEW.toolFailureCode IS NOT NULL
                ))
            )
        )
        OR (
            NEW.role <> 'tool' AND (
                NEW.toolCallId IS NOT NULL OR NEW.toolResultType IS NOT NULL
                OR NEW.toolResultPayloadJson IS NOT NULL OR NEW.toolFailureCode IS NOT NULL
                OR NEW.contentFormatVersion IS NULL OR NEW.contentFormatVersion <= 0
                OR NEW.contentJson IS NULL
            )
        )
    """.trimIndent()

    private val invocationInvalid = """
        length(trim(NEW.conversationId)) = 0
        OR length(trim(NEW.toolCallId)) = 0
        OR length(trim(NEW.toolId)) = 0
        OR length(trim(NEW.argumentsJson)) = 0
        OR length(NEW.requestFingerprint) != 64
        OR NEW.requestFingerprint GLOB '*[^0-9a-f]*'
        OR NEW.fingerprintVersion <= 0
        OR NEW.status NOT IN (
            'pending_approval', 'approved', 'running', 'completed', 'failed',
            'denied', 'expired', 'cancelled', 'unknown_after_restart'
        )
        OR ((NEW.resultFormatVersion IS NULL) != (NEW.resultJson IS NULL))
        OR (NEW.resultFormatVersion IS NOT NULL AND NEW.resultFormatVersion <= 0)
        OR ((NEW.status IN ('completed', 'failed', 'denied')) != (NEW.resultJson IS NOT NULL))
        OR (NEW.status = 'pending_approval' AND NEW.approvalId IS NULL)
        OR (
            NOT (
                NEW.approvalId IS NULL
                AND NEW.approvalCreatedAtEpochSeconds IS NULL
                AND NEW.approvalCreatedAtNano IS NULL
                AND NEW.approvalExpiresAtEpochSeconds IS NULL
                AND NEW.approvalExpiresAtNano IS NULL
                AND NEW.approvalCreatedElapsedRealtimeMillis IS NULL
                AND NEW.approvalExpiresElapsedRealtimeMillis IS NULL
                AND NEW.approvalBootCount IS NULL
            )
            AND NOT (
                NEW.approvalId IS NOT NULL
                AND NEW.approvalCreatedAtEpochSeconds IS NOT NULL
                AND NEW.approvalCreatedAtNano IS NOT NULL
                AND NEW.approvalExpiresAtEpochSeconds IS NOT NULL
                AND NEW.approvalExpiresAtNano IS NOT NULL
                AND NEW.approvalCreatedElapsedRealtimeMillis IS NOT NULL
                AND NEW.approvalExpiresElapsedRealtimeMillis IS NOT NULL
                AND NEW.approvalBootCount IS NOT NULL
            )
        )
        OR (NEW.approvalId IS NOT NULL AND (
            length(trim(NEW.approvalId)) = 0
            OR NEW.approvalCreatedAtNano NOT BETWEEN 0 AND 999999999
            OR NEW.approvalExpiresAtNano NOT BETWEEN 0 AND 999999999
            OR NEW.approvalExpiresAtEpochSeconds < NEW.approvalCreatedAtEpochSeconds
            OR (NEW.approvalExpiresAtEpochSeconds = NEW.approvalCreatedAtEpochSeconds
                AND NEW.approvalExpiresAtNano <= NEW.approvalCreatedAtNano)
            OR NEW.approvalCreatedElapsedRealtimeMillis < 0
            OR NEW.approvalExpiresElapsedRealtimeMillis <= NEW.approvalCreatedElapsedRealtimeMillis
            OR NEW.approvalBootCount < 0
        ))
    """.trimIndent()

    private val guards = listOf(
        Guard("conversations", "INSERT", conversationInvalid),
        Guard("conversations", "UPDATE", conversationInvalid),
        Guard("messages", "INSERT", messageInvalid),
        Guard("messages", "UPDATE", messageInvalid),
        Guard("tool_invocations", "INSERT", invocationInvalid),
        Guard("tool_invocations", "UPDATE", invocationInvalid),
    )
}
