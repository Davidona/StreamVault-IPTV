package com.streamvault.data.webhook

import android.content.Context
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.playback.events.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

@Singleton
class PlaybackWebhookService @Inject constructor(
    @ApplicationContext context: Context,
    crypto: CredentialCrypto,
) : PlaybackWebhooks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PlaybackWebhookStore(File(context.noBackupFilesDir, "playback_webhooks"), crypto)
    private val sender = PlaybackWebhookSender()
    private val gate = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val mutableConfiguration = MutableStateFlow(PlaybackWebhookConfiguration())
    override val configuration = mutableConfiguration.asStateFlow()
    // The empty key represents store readiness/errors, never a destination.
    private val mutableDiagnostics = MutableStateFlow<Map<String, PlaybackWebhookDiagnostic>>(
        mapOf("" to PlaybackWebhookDiagnostic(PlaybackWebhookStatus.IDLE)))
    override val diagnostics = mutableDiagnostics.asStateFlow()
    @Volatile private var state = StoredWebhooks()
    private val pending = mutableListOf<PendingWebhook>()
    private val workers = mutableMapOf<String, Job>()
    private val signals = mutableMapOf<String, Channel<Unit>>()
    private val input = Channel<PendingWebhook>(256)
    private val inFlight = mutableMapOf<String, PendingWebhook>()
    private val planners = mutableMapOf<String, PlaybackEventPlanner>()
    private var owner: String? = null
    private var latest: PlaybackObservation? = null
    private var corruptStorage = false
    private val appContext = context.applicationContext

    init {
        scope.launch {
            gate.withLock {
                try {
                    state = store.read()
                    pending.addAll(state.pending.filter { item ->
                        item.durable && item.expiresAtMs > System.currentTimeMillis() &&
                            state.configuration.enabled && state.configuration.destinations.any {
                                it.enabled && it.id == item.destinationId && it.revision == item.revision
                            }
                    })
                    mutableConfiguration.value = state.configuration
                    mutableDiagnostics.value = diagnostics.value - ""
                } catch (_: Exception) {
                    corruptStorage = true
                    diagnostic("", PlaybackWebhookStatus.STORAGE_ERROR)
                }
                startWorkers()
            }
            ready.complete(Unit)
            for (item in input) gate.withLock {
                val destination = configuration.value.destinations.firstOrNull {
                    configuration.value.enabled && it.enabled && it.id == item.destinationId && it.revision == item.revision
                } ?: return@withLock
                pending.removeAll { it.expiresAtMs <= System.currentTimeMillis() }
                // Progress never displaces transitions. Current-state automation skips obsolete states.
                if (item.type == PlaybackEventType.PROGRESS) pending.removeAll {
                    it.destinationId == item.destinationId && it.type == PlaybackEventType.PROGRESS
                }
                if (destination.delivery == PlaybackWebhookDelivery.REALTIME && item.type != PlaybackEventType.WATCHED) {
                    pending.removeAll { it.destinationId == item.destinationId &&
                        it.type != PlaybackEventType.WATCHED && it !== inFlight[item.destinationId] }
                }
                if (pending.count { it.destinationId == item.destinationId } >= 256) {
                    diagnostic(item.destinationId, PlaybackWebhookStatus.OVERFLOW)
                    return@withLock
                }
                pending += item
                if (persist()) signals[item.destinationId]?.trySend(Unit)
                diagnostic(item.destinationId, diagnostics.value[item.destinationId]?.status ?: PlaybackWebhookStatus.IDLE,
                    pendingCount = pending.count { it.destinationId == item.destinationId })
            }
        }
    }

    override suspend fun save(configuration: PlaybackWebhookConfiguration) {
        PlaybackWebhookStore.validate(configuration)
        ready.await()
        gate.withLock {
            val old = this.configuration.value
            val updated = configuration.copy(destinations = configuration.destinations.map { destination ->
                val previous = old.destinations.firstOrNull { it.id == destination.id }
                if (previous == destination && old.enabled == configuration.enabled) destination
                else destination.copy(revision = UUID.randomUUID().toString())
            })
            val retained = pending.filter { item -> updated.enabled && updated.destinations.any {
                it.enabled && it.id == item.destinationId && it.revision == item.revision
            } }
            withContext(Dispatchers.IO) { store.write(state.copy(configuration = updated, pending = retained.filter { it.durable })) }
            state = state.copy(configuration = updated)
            corruptStorage = false
            val changedIds = (old.destinations + updated.destinations).map { it.id }.toSet().filter { id ->
                old.enabled != updated.enabled || old.destinations.firstOrNull { it.id == id } != updated.destinations.firstOrNull { it.id == id }
            }.toSet()
            changedIds.forEach { id ->
                workers.remove(id)?.cancel()
                signals.remove(id)?.close()
                inFlight.remove(id)
            }
            pending.clear()
            pending.addAll(retained)
            synchronized(this) { changedIds.forEach { planners.remove(it) }; mutableConfiguration.value = updated }
            mutableDiagnostics.value = diagnostics.value.filterKeys { it.isNotEmpty() && it !in changedIds }
            startWorkers()
        }
    }

    @Synchronized override fun report(owner: String, observation: PlaybackObservation) {
        if (!configuration.value.enabled) return
        if (this.owner != owner) { planners.clear(); this.owner = owner }
        latest = observation
        configuration.value.destinations.filter { it.enabled }.forEach { destination ->
            val planner = planners.getOrPut(destination.id) { PlaybackEventPlanner(destination.policy) }
            planner.observe(observation).forEach { event ->
                if (event.event !in destination.events || event.media.type !in destination.mediaTypes) return@forEach
                val body = PlaybackWebhookPayload.encode(destination.format, event, state.installationId)
                if (body == null) {
                    diagnostic(destination.id, PlaybackWebhookStatus.SKIPPED)
                } else {
                    val durable = destination.delivery == PlaybackWebhookDelivery.HISTORY && event.event != PlaybackEventType.PROGRESS
                    val item = PendingWebhook(destination.id, destination.revision, event.eventId, event.sessionId,
                        event.event, body, System.currentTimeMillis() + if (durable) 86_400_000 else 30_000, durable)
                    if (body.length > 16_384 || input.trySend(item).isFailure) {
                        diagnostic(destination.id, PlaybackWebhookStatus.OVERFLOW)
                    }
                }
            }
        }
    }

    @Synchronized override fun finish(owner: String) {
        if (this.owner != owner) return
        latest?.let { report(owner, it.copy(phase = PlaybackEventPhase.STOPPED, timestamp = Instant.now())) }
        planners.clear()
        latest = null
        this.owner = null
    }

    private fun startWorkers() {
        if (!configuration.value.enabled) return
        configuration.value.destinations.filter { it.enabled }.forEach { destination ->
            if (workers.containsKey(destination.id)) return@forEach
            val signal = Channel<Unit>(Channel.CONFLATED)
            signals[destination.id] = signal
            workers[destination.id] = scope.launch {
                for (ignored in signal) {
                    while (isActive) {
                        val item = gate.withLock {
                            pending.firstOrNull { it.destinationId == destination.id }
                        } ?: break
                        val time = System.currentTimeMillis()
                        if (item.expiresAtMs <= time || item.attempts >= 5) {
                            gate.withLock {
                                pending.remove(item)
                                diagnostic(destination.id, PlaybackWebhookStatus.EXPIRED)
                                persist()
                            }
                            continue
                        }
                        if (item.nextAttemptMs > time) {
                            withTimeoutOrNull(minOf(item.nextAttemptMs, item.expiresAtMs).minus(time).coerceIn(1, 300_000)) { signal.receive() }
                            continue
                        }
                        val active = gate.withLock {
                            if (item !in pending || item.expiresAtMs <= System.currentTimeMillis()) false
                            else { inFlight[destination.id] = item; true }
                        }
                        if (!active) continue
                        val result = try {
                            sender.sendJson(PlaybackWebhookStore.endpoint(destination), item.eventId, item.body)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            WebhookDeliveryResult(WebhookDeliveryCategory.REJECTED)
                        }
                        gate.withLock {
                            if (configuration.value.destinations.none { it.id == destination.id && it.revision == destination.revision }) return@withLock
                            inFlight.remove(destination.id)
                            item.attempts++
                            val status = when (result.category) {
                                WebhookDeliveryCategory.SUCCESS -> PlaybackWebhookStatus.SENT
                                WebhookDeliveryCategory.REJECTED -> PlaybackWebhookStatus.REJECTED
                                WebhookDeliveryCategory.RETRYABLE -> PlaybackWebhookStatus.RETRYING
                            }
                            if (status != PlaybackWebhookStatus.RETRYING) pending.remove(item)
                            else item.nextAttemptMs = System.currentTimeMillis() +
                                (result.retryAfterMs ?: Random.nextLong(1000, 2000) * (1L shl item.attempts.coerceAtMost(4)))
                            diagnostic(destination.id, status, result.httpStatus,
                                pending.count { it.destinationId == destination.id })
                            persist()
                        }
                    }
                }
            }
            signal.trySend(Unit)
        }
    }

    private fun persist(): Boolean = try {
        if (corruptStorage) false else {
            store.write(state.copy(pending = pending.filter { it.durable }))
            if (pending.any { it.durable }) {
                // Immediate delivery is still useful if background scheduling is unavailable.
                runCatching { PlaybackWebhookRecoveryWorker.schedule(appContext) }
            }
            true
        }
    } catch (_: Exception) {
        diagnostic("", PlaybackWebhookStatus.STORAGE_ERROR)
        false
    }

    @Synchronized private fun diagnostic(id: String, status: PlaybackWebhookStatus, httpStatus: Int? = null,
        pendingCount: Int = diagnostics.value[id]?.pending ?: 0) {
        mutableDiagnostics.value = mutableDiagnostics.value + (id to PlaybackWebhookDiagnostic(status,
            httpStatus, pendingCount, System.currentTimeMillis()))
    }

    override fun preview(destination: PlaybackWebhookDestination): String? {
        val event = PlaybackEvent("preview", "preview", 1, PlaybackEventType.STOP, Instant.EPOCH,
            PlaybackMedia(PlaybackMediaType.MOVIE, "StreamVault", ids = PlaybackExternalIds(tmdb = 603)),
            90_000, 100_000, 30_000, true, PlaybackStopReason.USER)
        return PlaybackWebhookPayload.encode(destination.format, event, state.installationId)
    }

    override suspend fun test(destinationId: String) {
        ready.await()
        val destination = configuration.value.destinations.firstOrNull { it.id == destinationId } ?: return
        if (destination.format != PlaybackWebhookFormat.NATIVE) {
            diagnostic(destinationId, PlaybackWebhookStatus.SKIPPED)
            return
        }
        val testId = UUID.randomUUID().toString()
        val json = com.google.gson.JsonParser.parseString(preview(destination)).asJsonObject.apply {
            addProperty("event", "test")
            addProperty("event_id", testId)
            addProperty("session_id", testId)
            addProperty("timestamp", Instant.now().toString())
            addProperty("watched", false)
            addProperty("position_ms", 0)
            addProperty("played_ms", 0)
            remove("ids")
        }.toString()
        val result = sender.sendJson(PlaybackWebhookStore.endpoint(destination), testId, json)
        diagnostic(destinationId, when (result.category) {
            WebhookDeliveryCategory.SUCCESS -> PlaybackWebhookStatus.SENT
            WebhookDeliveryCategory.REJECTED -> PlaybackWebhookStatus.REJECTED
            WebhookDeliveryCategory.RETRYABLE -> PlaybackWebhookStatus.RETRYING
        }, result.httpStatus)
    }

    internal suspend fun awaitRecovery(): Boolean {
        ready.await()
        repeat(20) {
            if (gate.withLock { pending.none { it.durable } }) return true
            delay(1000)
        }
        return false
    }

    internal fun shutdown() { scope.cancel() }
}
