package com.streamvault.data.local

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ProviderActivePersistenceTest {

    private val databaseName = "provider-active-persistence-test"
    private lateinit var context: Context
    private lateinit var databaseDirectory: File
    private lateinit var databaseFile: File
    private lateinit var database: StreamVaultDatabase

    @Before
    fun setUp() {
        databaseDirectory = Files.createTempDirectory("streamvault-provider-active-").toFile()
        databaseFile = File(databaseDirectory, databaseName)
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getDatabasePath(name: String): File = databaseFile
        }
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        databaseDirectory.deleteRecursively()
    }

    @Test
    fun `third provider remains active after selecting it among six and reopening database`() = runTest {
        val providerDao = database.providerDao()
        (1L..6L).forEach { id ->
            providerDao.insert(provider(id = id, isActive = true))
        }

        providerDao.setActive(3L)
        database.close()
        database = openDatabase()

        val reopenedDao = database.providerDao()
        assertThat(reopenedDao.getAllSync()).hasSize(6)
        assertThat(reopenedDao.getAllSync().filter(ProviderEntity::isActive).map(ProviderEntity::id))
            .containsExactly(3L)
        assertThat(reopenedDao.getActive().first()?.id).isEqualTo(3L)
    }

    private fun openDatabase(): StreamVaultDatabase = Room.databaseBuilder(
        context,
        StreamVaultDatabase::class.java,
        databaseName
    )
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .allowMainThreadQueries()
        .build()

    private fun provider(id: Long, isActive: Boolean) = ProviderEntity(
        id = id,
        name = "Provider $id",
        type = ProviderType.M3U,
        isActive = isActive
    )
}
