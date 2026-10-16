package com.streamvault.app.trakt

import android.app.Application
import android.content.ContextWrapper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.streamvault.data.security.AndroidKeystoreCredentialCrypto
import com.streamvault.data.trakt.*
import com.streamvault.domain.trakt.*
import com.streamvault.feature.playback.player.PlayerTraktCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Synthetic account + HTTP server; no real Trakt requests or watched-history changes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TraktPlaybackIntegrationTest {
    @Test fun playerTransitionsReachAuthenticatedMovieAndResolvedEpisodeRequests() = runBlocking {
        val target = RuntimeEnvironment.getApplication()
        val directory = File(target.cacheDir, "trakt-playback-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/users/settings" -> MockResponse().setBody("""{"user":{"username":"synthetic-viewer"}}""")
                        "/search/tmdb/1396?type=show" -> MockResponse().setBody("""[{"type":"show","show":{"ids":{"tmdb":1396,"trakt":1}}}]""")
                        "/shows/1/seasons/2/episodes/3" -> MockResponse().setBody("""{"season":2,"number":3,"ids":{"trakt":123}}""")
                        "/scrobble/start", "/scrobble/pause", "/scrobble/stop" -> MockResponse().setResponseCode(201).setBody("{}")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val store = EncryptedTraktTokenStore(context, AndroidKeystoreCredentialCrypto(target))
                val now = System.currentTimeMillis() / 1000
                store.write(TraktStoredSession(TraktTokens("synthetic-access", "synthetic-refresh", now, 3600)))
                val account = TraktAuthService(TraktConfig("synthetic-client", "https://example.org/callback"), store,
                    TraktApiClient(TraktConfig("synthetic-client", "https://example.org/callback"),
                        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(), server.url("/")))
                val completed = Channel<Pair<TraktScrobbleEvent, TraktScrobbleOutcome>>(Channel.UNLIMITED)
                val sender = object : TraktScrobbler {
                    override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                        val outcome = account.send(event)
                        completed.send(event to outcome)
                        return outcome
                    }
                }
                val coordinator = PlayerTraktCoordinator(account, sender, scope)
                withTimeout(10_000) { account.state.first { it.username == "synthetic-viewer" } }
                assertEquals("/users/settings", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                val movie = TraktMediaIdentity.MovieId(603)
                val episode = TraktMediaIdentity.EpisodeId(1396, 2, 3)
                suspend fun sent(action: TraktScrobbleAction, identity: TraktMediaIdentity) {
                    val result = withTimeout(10_000) { completed.receive() }
                    assertEquals(TraktScrobbleOutcome.SENT, result.second)
                    assertEquals(action, result.first.action)
                    assertEquals(identity, result.first.media)
                }
                coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
                sent(TraktScrobbleAction.START, movie)
                coordinator.report(1, movie, TraktPlaybackPhase.PAUSED, 200, 1000)
                sent(TraktScrobbleAction.PAUSE, movie)
                coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 200, 1000)
                sent(TraktScrobbleAction.START, movie)
                coordinator.report(2, episode, TraktPlaybackPhase.PLAYING, 50, 1000)
                sent(TraktScrobbleAction.STOP, movie)
                sent(TraktScrobbleAction.START, episode)
                coordinator.report(2, episode, TraktPlaybackPhase.ENDED, 1000, 1000)
                sent(TraktScrobbleAction.STOP, episode)
                coordinator.close()

                val requests = List(8) { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
                assertEquals(listOf("/scrobble/start", "/scrobble/pause", "/scrobble/start", "/scrobble/stop",
                    "/search/tmdb/1396?type=show", "/shows/1/seasons/2/episodes/3", "/scrobble/start", "/scrobble/stop"), requests.map { it.path })
                assertTrue(requests.all { it.getHeader("Authorization") == "Bearer synthetic-access" })
                assertTrue(requests.all { it.getHeader("trakt-api-key") == "synthetic-client" })
                val lastBody = Gson().fromJson(requests.last().body.readUtf8(), JsonObject::class.java)
                assertEquals(123, lastBody.getAsJsonObject("episode").getAsJsonObject("ids")["trakt"].asInt)
                assertEquals(100.0, lastBody["progress"].asDouble, .001)
                assertTrue(File(directory, "trakt_session").readText().startsWith("enc:v1:"))
            }
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
