package com.streamvault.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.subtitle.OpenSubtitlesApi
import com.streamvault.data.remote.subtitle.OpenSubtitlesSessionStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OpenSubtitlesRepositoryImplTest {
    private lateinit var context: Context
    private lateinit var repository: OpenSubtitlesRepositoryImpl
    private lateinit var durableDirectory: File
    private lateinit var legacyDirectory: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = OpenSubtitlesRepositoryImpl(
            context = context,
            api = mock<OpenSubtitlesApi>(),
            sessionStore = mock<OpenSubtitlesSessionStore>(),
        )
        durableDirectory = File(context.filesDir, "subtitles/opensubtitles").apply { mkdirs() }
        legacyDirectory = File(context.cacheDir, "subtitles/opensubtitles").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        durableDirectory.deleteRecursively()
        legacyDirectory.deleteRecursively()
    }

    @Test
    fun `legacy cache file is moved to durable storage and removed`() = runBlocking {
        val legacy = File(legacyDirectory, "123.srt").apply { writeText("subtitle") }

        val cached = repository.cachedSubtitleFile("123")

        assertThat(cached).isNotNull()
        assertThat(cached!!.readText()).isEqualTo("subtitle")
        assertThat(cached.parentFile).isEqualTo(durableDirectory)
        assertThat(legacy.exists()).isFalse()
    }

    @Test
    fun `delete removes both durable and legacy cached files`() = runBlocking {
        val durable = File(durableDirectory, "456.srt").apply { writeText("subtitle") }
        val legacy = File(legacyDirectory, "456.srt").apply { writeText("subtitle") }

        repository.deleteCachedSubtitle("456")

        assertThat(durable.exists()).isFalse()
        assertThat(legacy.exists()).isFalse()
    }
}
