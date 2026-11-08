package com.aistudio.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageDatabaseFactoryInstrumentedTest {
    @Test
    fun productionBuilderOpensDatabaseWithAndroidSqliteDriver() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "storage-driver-${UUID.randomUUID()}.db"
        val database = StorageDatabaseFactory.persistentBuilder(context, databaseName).build()
        try {
            assertNull(database.conversationDao().getById("missing"))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
