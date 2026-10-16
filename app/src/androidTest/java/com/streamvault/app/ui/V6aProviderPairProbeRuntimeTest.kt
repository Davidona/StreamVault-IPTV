package com.streamvault.app.ui

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.internal.GeneratedComponentManager
import java.net.URI
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only discovery probe for a real provider's duplicate movie candidates. */
@RunWith(AndroidJUnit4::class)
class V6aProviderPairProbeRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun component(name: String): Any {
        val generated = (app as GeneratedComponentManager<*>).generatedComponent()
        return (generated.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(generated) as Provider<*>).get()!!
    }

    @Test
    fun discoverDuplicateMovieCandidates() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v6aProviderProbe") == "true")
        val providers = component("providerRepositoryImplProvider") as ProviderRepository
        val movies = component("movieRepositoryImplProvider") as MovieRepository
        val activeId = providers.getActiveProvider().first()?.id ?: error("No active provider")
        val all = movies.getMovies(activeId).first()
        val candidates = all
            .groupBy { logicalKey(it) }
            .values
            .filter { group -> group.size >= 2 && group.mapNotNull { it.containerExtension?.lowercase() }.toSet().size >= 2 }
            .sortedWith(compareByDescending<List<Movie>> { it.mapNotNull(Movie::containerExtension).toSet().size }.thenByDescending { it.size })
            .take(12)

        Log.i("V6aProviderProbe", "activeProvider=$activeId movies=${all.size} groups=${candidates.size}")
        check(candidates.isNotEmpty()) { "No same-title candidates with distinct containers" }
        candidates.forEachIndexed { index, group ->
            val rows = group.take(4).map { movie ->
                val detail = movies.getMovieDetails(activeId, movie.id).getOrNull() ?: movie
                val stream = movies.getStreamInfo(detail).getOrNull()
                val variants = movies.getMovieVariants(movie.id)
                val safeLocation = stream?.url?.let(::safeLocation) ?: "unresolved"
                "id=${movie.id},stream=${movie.streamId},ext=${detail.containerExtension},duration=${detail.durationSeconds},year=${detail.year},type=${stream?.streamType},location=$safeLocation,variants=${variants.size}"
            }
            Log.i("V6aProviderProbe", "candidate[$index] key=${logicalKey(group.first())} ${rows.joinToString(" || ")}")
        }
    }

    private fun logicalKey(movie: Movie): String = buildString {
        append(movie.name.trim().lowercase())
        append('|')
        append(movie.year ?: Regex("(19|20)\\d{2}").find(movie.name)?.value.orEmpty())
    }

    private fun safeLocation(url: String): String = runCatching {
        val uri = URI(url)
        buildString {
            append(uri.scheme ?: "<scheme>")
            append("://")
            append(uri.host ?: "<host>")
            if (uri.port >= 0) append(':').append(uri.port)
            append(uri.path ?: "")
        }
    }.getOrDefault("<unparseable>")
}
