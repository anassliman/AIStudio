package com.aistudio.storage

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Test-only builder so the bundled native SQLite driver stays out of production dependencies. */
internal object TestStorageDatabaseFactory {
    fun inMemoryBuilder(): RoomDatabase.Builder<StorageDatabase> =
        Room.inMemoryDatabaseBuilder<StorageDatabase>()
            .setDriver(BundledSQLiteDriver())
            .addCallback(StorageDatabaseIntegrity.callback)
}
