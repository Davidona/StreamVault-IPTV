package com.streamvault.domain.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioInstalledAddon
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioManifestResource
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaDetail
import com.streamvault.domain.model.stremio.StremioResourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StremioResourceResolverTest {
    private val resolver = StremioResourceResolver()

    @Test
    fun `resource candidates honor exact type prefix enabled state and catalog origin` () {
        val catalogOnly = installed(
            1,
            resources = listOf(StremioManifestResource(StremioResourceKind.CATALOG)),
            catalogs = listOf(
                com.streamvault.domain.model.stremio.StremioCatalogRef(StremioMediaType.MOVIE, "top")
            )
        )
        val streamOnly = installed(
            2,
            types = listOf(StremioMediaType.MOVIE),
            idPrefixes = listOf("tt"),
            resources = listOf(
                StremioManifestResource(
                    StremioResourceKind.STREAM,
                    types = listOf(StremioMediaType.MOVIE),
                    idPrefixes = listOf("tt")
                )
            )
        )
        val subtitleOnly = installed(
            3,
            types = listOf(StremioMediaType.MOVIE),
            idPrefixes = listOf("tt"),
            resources = listOf(StremioManifestResource(StremioResourceKind.SUBTITLES, isStringResource = true))
        )
        val unrelatedPrefix = installed(
            4,
            types = listOf(StremioMediaType.MOVIE),
            idPrefixes = listOf("kitsu"),
            resources = listOf(
                StremioManifestResource(
                    StremioResourceKind.STREAM,
                    idPrefixes = listOf("kitsu")
                )
            )
        )
        val explicitlyEmptyPrefixes = installed(
            5,
            types = listOf(StremioMediaType.MOVIE),
            idPrefixes = emptyList(),
            resources = listOf(
                StremioManifestResource(StremioResourceKind.STREAM, idPrefixes = emptyList())
            )
        )
        val disabled = installed(
            6,
            types = listOf(StremioMediaType.MOVIE),
            idPrefixes = listOf("tt"),
            resources = listOf(
                StremioManifestResource(StremioResourceKind.STREAM, idPrefixes = listOf("tt"))
            ),
            enabled = false
        )
        val addons = listOf(
            catalogOnly,
            streamOnly,
            subtitleOnly,
            unrelatedPrefix,
            explicitlyEmptyPrefixes,
            disabled
        )

        assertThat(
            resolver.candidates(addons, StremioResourceKind.STREAM, StremioMediaType.MOVIE, "tt123")
                .map { it.addon.id }
        ).containsExactly(2L).inOrder()
        assertThat(
            resolver.candidates(addons, StremioResourceKind.SUBTITLES, StremioMediaType.MOVIE, "tt123")
                .map { it.addon.id }
        ).containsExactly(3L).inOrder()
        assertThat(
            resolver.candidates(
                addons,
                StremioResourceKind.CATALOG,
                StremioMediaType.MOVIE,
                "top"
            ).map { it.addon.id }
        ).containsExactly(1L)
        assertThat(
            resolver.candidates(
                addons,
                StremioResourceKind.CATALOG,
                StremioMediaType.MOVIE,
                "tt123"
            )
        ).isEmpty()
    }

    @Test
    fun `resource filters preserve omitted versus explicitly empty type lists`() {
        val inherited = installed(
            1,
            types = listOf(StremioMediaType.MOVIE),
            resources = listOf(StremioManifestResource(StremioResourceKind.META))
        )
        val matchNone = installed(
            2,
            types = listOf(StremioMediaType.MOVIE),
            resources = listOf(StremioManifestResource(StremioResourceKind.META, types = emptyList()))
        )
        val unrestricted = installed(
            3,
            resources = listOf(StremioManifestResource(StremioResourceKind.META))
        )

        assertThat(
            resolver.candidates(
                listOf(inherited, matchNone, unrestricted),
                StremioResourceKind.META,
                StremioMediaType.MOVIE,
                "custom-id"
            ).map { it.addon.id }
        ).containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun `metadata prefers the catalog origin then falls through failed providers`() = runTest {
        val addons = listOf(1L, 2L, 3L).map { id ->
            installed(
                id,
                types = listOf(StremioMediaType.MOVIE),
                resources = listOf(StremioManifestResource(StremioResourceKind.META))
            )
        }
        val candidates = resolver.candidates(
            addons,
            StremioResourceKind.META,
            StremioMediaType.MOVIE,
            "tt123",
            preferredAddonId = 2L
        )
        val requested = mutableListOf<Long>()

        val result = resolver.resolveMeta(candidates, StremioMediaType.MOVIE, "tt123") { addon ->
            requested += addon.id
            when (addon.id) {
                2L -> Result.error("provider offline")
                1L -> Result.success(meta("wrong-id"))
                else -> Result.success(meta("tt123"))
            }
        }

        assertThat(requested).containsExactly(2L, 1L, 3L).inOrder()
        assertThat(result.result?.source?.addonId).isEqualTo(3L)
        assertThat(result.failures.map { it.source.addonId }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `fanout preserves install order partial results and bounds concurrency`() = runTest {
        val candidates = (1L..7L).map { id ->
            installed(
                id,
                types = listOf(StremioMediaType.MOVIE),
                resources = listOf(StremioManifestResource(StremioResourceKind.STREAM))
            )
        }
        var inFlight = 0
        var maxInFlight = 0
        val result = resolver.resolveMany(candidates) { addon ->
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            delay(10)
            inFlight--
            if (addon.id == 2L) Result.error("provider offline") else Result.success(listOf(addon.id))
        }

        assertThat(maxInFlight).isAtMost(4)
        assertThat(result.results.map { it.resource }).containsExactly(1L, 3L, 4L, 5L, 6L, 7L).inOrder()
        assertThat(result.results.map { it.source.addonId }).containsExactly(1L, 3L, 4L, 5L, 6L, 7L).inOrder()
        assertThat(result.failures.single().source.addonId).isEqualTo(2L)
        assertThat(result.candidateCount).isEqualTo(7)
    }

    @Test
    fun `cancelling fanout cancels outstanding addon requests`() = runTest {
        val started = CompletableDeferred<Unit>()
        val candidate = installed(
            1,
            types = listOf(StremioMediaType.MOVIE),
            resources = listOf(StremioManifestResource(StremioResourceKind.STREAM))
        )
        val request = async {
            resolver.resolveMany<Long>(listOf(candidate)) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        request.cancelAndJoin()
        assertThat(request.isCancelled).isTrue()
    }

    private fun installed(
        id: Long,
        types: List<StremioMediaType>? = null,
        idPrefixes: List<String>? = null,
        resources: List<StremioManifestResource>,
        catalogs: List<com.streamvault.domain.model.stremio.StremioCatalogRef> = emptyList(),
        enabled: Boolean = true
    ): StremioInstalledAddon {
        val addon = StremioAddon(
            id = id,
            installUrl = "https://example.test/$id/manifest.json",
            manifestId = "test.$id",
            name = "Addon $id",
            version = "1.0.0",
            enabled = enabled
        )
        val manifest = StremioManifest(
            id = addon.manifestId,
            name = addon.name,
            version = addon.version,
            types = types,
            idPrefixes = idPrefixes,
            resources = resources,
            catalogs = catalogs
        )
        return StremioInstalledAddon(addon, manifest)
    }

    private fun meta(id: String) = StremioMetaDetail(
        id = id,
        type = StremioMediaType.MOVIE,
        name = id
    )
}
