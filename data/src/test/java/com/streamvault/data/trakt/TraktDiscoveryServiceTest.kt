package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktDiscoveryCategory
import com.streamvault.domain.trakt.TraktDiscoveryKind
import com.streamvault.domain.trakt.TraktDiscoveryMedia
import com.streamvault.domain.trakt.TraktDiscoveryPage
import com.streamvault.domain.trakt.TraktDiscoveryRefreshResult
import com.streamvault.domain.trakt.TraktDiscoverySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktDiscoveryServiceTest {
    private class Source : TraktDiscoverySource {
        var page = TraktDiscoveryPage(emptyList())
        var shouldFail = false
        var reads = 0

        override suspend fun discover(category: TraktDiscoveryCategory, page: Int): TraktDiscoveryPage {
            reads++
            assertEquals(1, page)
            if (shouldFail) error("offline")
            return this.page
        }
    }

    @Test fun `successful discovery pages are sanitized and cached until forced`() = runBlocking {
        val source = Source().apply { page = TraktDiscoveryPage(listOf(603, 603, -1, 550), skippedItems = 2) }
        val service = TraktDiscoveryService(source)
        val category = TraktDiscoveryCategory(TraktDiscoveryMedia.MOVIE, TraktDiscoveryKind.TRENDING)

        assertEquals(TraktDiscoveryRefreshResult.Success(2, skipped = 2), service.refresh(category))
        assertEquals(listOf(603L, 550L), service.state.value.items(category))
        assertTrue(service.state.value.isLoaded(category))
        assertEquals(TraktDiscoveryRefreshResult.Success(2), service.refresh(category))
        assertEquals(1, source.reads)

        source.page = TraktDiscoveryPage(listOf(550, 603))
        service.refresh(category, force = true)
        assertEquals(listOf(550L, 603L), service.state.value.items(category))
        assertEquals(2, source.reads)
    }

    @Test fun `failed refresh keeps a previous good page and marks the category failed`() = runBlocking {
        val source = Source().apply { page = TraktDiscoveryPage(listOf(603, 550)) }
        val service = TraktDiscoveryService(source)
        val category = TraktDiscoveryCategory(TraktDiscoveryMedia.MOVIE, TraktDiscoveryKind.POPULAR)
        service.refresh(category)
        source.shouldFail = true

        assertEquals(TraktDiscoveryRefreshResult.Failure, service.refresh(category, force = true))
        assertEquals(listOf(603L, 550L), service.state.value.items(category))
        assertTrue(service.state.value.isLoaded(category))
        assertTrue(category in service.state.value.failed)
        assertFalse(category in service.state.value.refreshing)
    }
}
