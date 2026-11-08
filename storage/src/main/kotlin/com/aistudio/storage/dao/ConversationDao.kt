package com.aistudio.storage.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.aistudio.storage.entity.ConversationEntity

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE archivedAtEpochSeconds IS NULL ORDER BY updatedAtEpochSeconds DESC, updatedAtNano DESC")
    suspend fun listActive(): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY updatedAtEpochSeconds DESC, updatedAtNano DESC")
    suspend fun listAll(): List<ConversationEntity>

    /** Low-level physical purge; Core has no deletion contract and callers must not expose this directly. */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deletePermanentlyById(id: String): Int
}
