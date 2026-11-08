package com.aistudio.storage

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

/** Centralizes driver and integrity-callback setup; this is database construction, not repository logic. */
object StorageDatabaseFactory {
    fun persistentBuilder(context: Context, databaseName: String): RoomDatabase.Builder<StorageDatabase> {
        require(databaseName.isNotBlank()) { "Database name must not be blank" }
        return Room.databaseBuilder<StorageDatabase>(context.applicationContext, databaseName)
            .setDriver(AndroidSQLiteDriver())
            .addCallback(StorageDatabaseIntegrity.callback)
    }
}
