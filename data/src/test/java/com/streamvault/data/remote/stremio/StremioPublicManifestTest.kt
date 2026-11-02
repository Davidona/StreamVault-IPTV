package com.streamvault.data.remote.stremio

import com.streamvault.domain.model.stremio.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Snapshots fetched from the public unconfigured endpoints on 2026-11-02; no user tokens. */
class StremioPublicManifestTest {
    private val client=StremioAddonClient(OkHttpClient(),Json { ignoreUnknownKeys=true;isLenient=true;coerceInputValues=true })
    private fun manifest(name:String)=client.parseManifest(javaClass.getResource("/stremio/public/$name-2026-11-02.json")!!.readText())!!
    @Test fun unconfiguredPenguPlayHasNoCatalogButRoutesItsDeclaredMovieAndTvResources() {
        val manifest=manifest("penguplay")
        assertEquals("com.penguplay",manifest.id)
        assertTrue(manifest.catalogs.isEmpty())
        assertTrue(manifest.supportsResource(StremioResourceKind.STREAM,StremioMediaType.MOVIE,"tt123"))
        assertTrue(manifest.supportsResource(StremioResourceKind.META,StremioMediaType.TV,"pp-live:channel"))
        assertFalse(manifest.supportsResource(StremioResourceKind.META,StremioMediaType.MOVIE,"tt123"))
    }
    @Test fun cncVersePreservesOtherAndTvCatalogs() {
        val manifest=manifest("cncverse")
        assertEquals("com.cncverse.stremiobridge",manifest.id)
        assertTrue(manifest.catalogs.any { it.type==StremioMediaType.TV })
        assertTrue(manifest.catalogs.any { it.type.typeName=="other" })
        assertTrue(manifest.supportsResource(StremioResourceKind.META,StremioMediaType.TV,"cnc:channel"))
    }
}
