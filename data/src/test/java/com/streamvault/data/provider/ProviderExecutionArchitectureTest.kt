package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.streams.toList
import org.junit.Test

class ProviderExecutionArchitectureTest {
    private val repositoryRoot: Path by lazy {
        generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }

    @Test
    fun `protocol clients are constructed only by the typed client factory`() {
        val allowed = setOf(
            "data/src/main/java/com/streamvault/data/provider/TypedProviderClientFactory.kt",
            "data/src/main/java/com/streamvault/data/remote/stalker/StalkerProvider.kt",
            "data/src/main/java/com/streamvault/data/remote/xtream/XtreamProvider.kt"
        )
        val constructorPattern = Regex("\\b(?:StalkerProvider|XtreamProvider)\\s*\\(")
        val violations = productionKotlinFiles()
            .filter { path ->
                constructorPattern.containsMatchIn(path.readText()) &&
                    path.repoRelativePath() !in allowed
            }
            .map { it.repoRelativePath() }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `runtime resolver boundaries do not branch on provider type`() {
        val boundaries = listOf(
            "data/src/main/java/com/streamvault/data/remote/xtream/XtreamStreamUrlResolver.kt",
            "data/src/main/java/com/streamvault/data/manager/RecordingManagerImpl.kt",
            "data/src/main/java/com/streamvault/data/epg/EpgResolutionEngine.kt"
        )
        val violations = boundaries.filter { relative ->
            repositoryRoot.resolve(relative).readText().contains("ProviderType.")
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `sync execution does not redispatch on provider type after registry resolution`() {
        val source = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/SyncManager.kt"
        ).readText()
        val providerTypeDispatch = Regex(
            """when\s*\(\s*(?:provider|providerEntity)\.type\s*\)"""
        )

        assertThat(providerTypeDispatch.containsMatchIn(source)).isFalse()
        assertThat(source).contains("syncCoordinator.syncFull(")
        assertThat(source).contains("syncCoordinator.syncSection(")
        assertThat(source).contains("syncCoordinator.syncGuide(")
        assertThat(source).contains("continuationScheduler = ProviderContinuationScheduler(providerSyncWorkScheduler)")
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/SyncManagerPlanDelegate.kt"
        ).readText()).contains("providerEpgExecutor.syncXtreamProviderEpg")
        val continuationScheduler = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/ProviderContinuationScheduler.kt"
        ).readText()
        assertThat(continuationScheduler).contains("workScheduler.scheduleBackgroundEpg(providerId)")
        assertThat(continuationScheduler).contains("workScheduler.scheduleXtreamIndex(")
        assertThat(continuationScheduler).contains("workScheduler.scheduleStalkerIndex(")
        assertThat(source).contains("private val catalogIndexJobStore = CatalogIndexJobStore(")
        assertThat(source).contains("catalogIndexJobStore.upsert(")
        assertThat(source).doesNotContain("val providerType = providerDao.getById(providerId)?.type")
        assertThat(source).doesNotContain("val existing = xtreamIndexJobDao.get(providerId, section)")
        assertThat(source).contains("return providerWorkLocks.withProviderLock(providerId, block)")
        assertThat(
            Regex(
                """private suspend fun <T> withStalkerSummaryLock[\s\S]{0,300}withProviderLock\(providerId\)"""
            ).containsMatchIn(source)
        ).isTrue()
        assertThat(source).contains("vodCategoryHydrationCoordinator")
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/VodCategoryHydrationCoordinator.kt"
        ).readText()).contains("providerSyncLocks.withVodCategoryLock(")
        assertThat(source).doesNotContain("providerSyncMutexes")
        assertThat(source).doesNotContain("providerEpgMutexes")
        assertThat(source).doesNotContain("syncAdmissionMutex")
        assertThat(source).doesNotContain("scheduleBackgroundEpg = ::scheduleBackgroundEpgSync")
        assertThat(source).doesNotContain("CatalogSyncPlanFactory.create(")
        assertThat(source).doesNotContain("providerSyncRegistry.resolve(snapshot)")
        assertThat(source).doesNotContain("syncAdapter.syncFull(")
        assertThat(source).doesNotContain("syncAdapter.syncSection(")
        assertThat(source).doesNotContain("syncAdapter.syncGuide(")
    }

    @Test
    fun `sync manager does not perform compatibility model projection`() {
        val manager = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/SyncManager.kt"
        ).readText()

        assertThat(manager).contains("SyncProviderSnapshotAdapter")
        assertThat(manager).contains("SyncManagerPlanDelegate")
        assertThat(manager).doesNotContain("toLegacyProvider")
        assertThat(manager).doesNotContain("toProviderSnapshot")
        assertThat(manager).doesNotContain("toSyncCompatibilityProvider")
    }

    @Test
    fun `index checkpoint and recovery helpers live outside sync manager`() {
        val manager = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/SyncManager.kt"
        ).readText()

        assertThat(manager).doesNotContain("getStalkerHydrationSnapshot")
        assertThat(manager).doesNotContain("markStalkerAttemptStarted")
        assertThat(manager).doesNotContain("markStalkerAttemptSucceeded")
        assertThat(manager).doesNotContain("markStalkerAttemptFailed")
        assertThat(manager).doesNotContain("stalkerIndexFailureState")
        assertThat(manager).doesNotContain("shouldRunXtreamSummaryIndex")
        assertThat(manager).doesNotContain("xtreamIndexFailureState")
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/StalkerIndexCheckpointStore.kt"
        ).readText()).contains("class StalkerIndexCheckpointStore")
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/StalkerIndexRecoveryPolicy.kt"
        ).readText()).contains("object StalkerIndexRecoveryPolicy")
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/XtreamIndexRecoveryPolicy.kt"
        ).readText()).contains("object XtreamIndexRecoveryPolicy")
    }

    @Test
    fun `workers and repositories consume narrow sync ports`() {
        val consumerPaths = listOf(
            "data/src/main/java/com/streamvault/data/repository/MovieRepositoryImpl.kt",
            "data/src/main/java/com/streamvault/data/repository/SeriesRepositoryImpl.kt",
            "data/src/main/java/com/streamvault/data/repository/VodRepositoryImpl.kt",
            "data/src/main/java/com/streamvault/data/repository/ProviderRepositoryImpl.kt",
            "data/src/main/java/com/streamvault/data/repository/ProviderDeletionCleanupWorker.kt",
            "data/src/main/java/com/streamvault/data/sync/ProviderSyncWorker.kt",
            "data/src/main/java/com/streamvault/data/sync/BackgroundEpgSyncWorker.kt",
            "data/src/main/java/com/streamvault/data/sync/XtreamIndexWorker.kt",
            "data/src/main/java/com/streamvault/data/sync/StalkerIndexWorker.kt",
            "data/src/main/java/com/streamvault/data/sync/ProviderSyncStateReaderImpl.kt"
        )

        consumerPaths.forEach { relative ->
            val source = repositoryRoot.resolve(relative).readText()
            assertThat(source).doesNotContain(": SyncManager")
            assertThat(source).doesNotContain("fun syncManager(): SyncManager")
        }

        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/ProviderSyncPorts.kt"
        ).toFile().isFile).isTrue()
    }

    @Test
    fun `small provider execution lives outside the sync manager`() {
        val manager = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/SyncManager.kt"
        ).readText()

        assertThat(manager).doesNotContain("private suspend fun syncM3u(")
        assertThat(manager).doesNotContain("private suspend fun syncM3uLiveOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncM3uMoviesOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncJellyfin(")
        assertThat(manager).doesNotContain("private suspend fun syncJellyfinMoviesOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncJellyfinSeriesOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncXtreamMoviesOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncXtreamSeriesOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncXtreamIndexFirst(")
        assertThat(manager).doesNotContain("private suspend fun syncXtreamLiveOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncXtreamCategoryShell(")
        assertThat(manager).doesNotContain("private suspend fun syncStalker(")
        assertThat(manager).doesNotContain("private suspend fun syncStalkerLiveOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncProviderEpg(")
        assertThat(manager).doesNotContain("private suspend fun syncXtreamProviderEpg(")
        assertThat(manager).doesNotContain("private suspend fun syncM3uProviderEpg(")
        assertThat(manager).doesNotContain("private suspend fun syncStalkerProviderEpg(")
        assertThat(manager).doesNotContain("private suspend fun syncXmlTvEpgOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncStalkerMoviesOnly(")
        assertThat(manager).doesNotContain("private suspend fun syncStalkerSeriesOnly(")
        assertThat(manager).doesNotContain("private suspend fun queueStalkerIndexSection(")
        assertThat(manager).doesNotContain("StalkerBrokenPerChannelEpgException")
        assertThat(manager).contains("private val stalkerIndexJobStore: StalkerIndexJobStore")
        assertThat(manager).doesNotContain("stalkerIndexJobDao.")
        assertThat(manager).doesNotContain("shouldRunStalkerSummaryIndex")
        assertThat(manager).doesNotContain("toLegacyJobState")

        val stalkerIndexJobStore = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/StalkerIndexJobStore.kt"
        ).readText()
        assertThat(stalkerIndexJobStore).contains("class StalkerIndexJobStore")
        assertThat(stalkerIndexJobStore).contains("StalkerIndexJobUpdate")
        assertThat(stalkerIndexJobStore).contains("fun shouldRunSummary")
        assertThat(stalkerIndexJobStore).contains("fun upsertLegacy")

        val epgExecutor = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/ProviderEpgSyncExecutor.kt"
        ).readText()
        assertThat(epgExecutor).contains("private suspend fun syncStalkerPreferredEpg(")
        assertThat(epgExecutor).contains("private suspend fun syncStalkerPortalEpg(")
        assertThat(epgExecutor).contains("stalkerRequestCoordinator.execute(")

        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/M3uCatalogSyncExecutor.kt"
        ).toFile().isFile).isTrue()
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/JellyfinCatalogSyncExecutor.kt"
        ).toFile().isFile).isTrue()
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/XtreamCatalogSectionExecutor.kt"
        ).toFile().isFile).isTrue()
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/XtreamCatalogSyncExecutor.kt"
        ).toFile().isFile).isTrue()
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/StalkerCatalogSectionExecutor.kt"
        ).toFile().isFile).isTrue()
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/StalkerCatalogSyncExecutor.kt"
        ).toFile().isFile).isTrue()
        assertThat(repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/ProviderEpgSyncExecutor.kt"
        ).toFile().isFile).isTrue()
    }

    @Test
    fun `major sync coordinators stay within explicit dependency budgets`() {
        val budgets = listOf(
            DependencyBudget(
                path = "data/src/main/java/com/streamvault/data/sync/SyncManager.kt",
                className = "SyncManager",
                // SyncManager currently assembles the provider executors, catalog/index
                // lifecycle, and durable backup-restore hooks. Keep this explicit rather
                // than letting the budget drift silently as those responsibilities evolve.
                maxConstructorDependencies = 43
            ),
            DependencyBudget(
                path = "data/src/main/java/com/streamvault/data/sync/SyncCoordinator.kt",
                className = "SyncCoordinator",
                maxConstructorDependencies = 2
            ),
            DependencyBudget(
                path = "data/src/main/java/com/streamvault/data/sync/VodCategoryHydrationCoordinator.kt",
                className = "VodCategoryHydrationCoordinator",
                maxConstructorDependencies = 11
            ),
            DependencyBudget(
                path = "data/src/main/java/com/streamvault/data/sync/StalkerIndexContinuationCoordinator.kt",
                className = "StalkerIndexContinuationCoordinator",
                maxConstructorDependencies = 11
            ),
            DependencyBudget(
                path = "data/src/main/java/com/streamvault/data/sync/StalkerIncrementalIndexExecutor.kt",
                className = "StalkerIncrementalIndexExecutor",
                maxConstructorDependencies = 1
            ),
            DependencyBudget(
                path = "data/src/main/java/com/streamvault/data/sync/XtreamIncrementalIndexExecutor.kt",
                className = "XtreamIncrementalIndexExecutor",
                maxConstructorDependencies = 1
            ),
            DependencyBudget(
                path = "data/src/main/java/com/streamvault/data/sync/SyncStatusPublicationCoordinator.kt",
                className = "SyncStatusPublicationCoordinator",
                maxConstructorDependencies = 3
            )
        )

        budgets.forEach { budget ->
            val source = repositoryRoot.resolve(budget.path).readText()
            val actual = constructorDependencyCount(source, budget.className)
            assertThat(actual).isAtMost(budget.maxConstructorDependencies)
        }

        val manager = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/SyncManager.kt"
        ).readText()
        val continuation = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/StalkerIndexContinuationCoordinator.kt"
        ).readText()
        assertThat(manager).contains("stalkerIndexContinuationCoordinator")
        assertThat(manager).contains("stalkerIncrementalIndexExecutor")
        assertThat(manager).doesNotContain("private suspend fun processStalkerSummaryIndexSection(")
        assertThat(manager).contains("xtreamIncrementalIndexExecutor.processSummary(")
        assertThat(manager).doesNotContain("private suspend fun processXtreamSummaryIndexSection(")
        assertThat(manager).contains("syncStatusPublicationCoordinator")
        assertThat(manager).doesNotContain("private val stateSessions")
        assertThat(manager).doesNotContain("private val progressSessions")
        assertThat(manager).doesNotContain("private suspend fun stalkerCatalogSectionState(")
        assertThat(manager).doesNotContain("private suspend fun scheduleNextStalkerCatalogStep(")
        assertThat(continuation).contains("suspend fun reconcileAtStartup()")
        assertThat(continuation).contains("scheduleNextSection")
        assertThat(continuation).contains("StalkerIndexPolicy.nextRetryDelaySeconds")

        val incremental = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/StalkerIncrementalIndexExecutor.kt"
        ).readText()
        assertThat(incremental).contains("suspend fun processSummary(")
        assertThat(incremental).contains("private suspend fun processWildcard(")
        assertThat(incremental).contains("StalkerIndexPolicy.detectPageAnomaly")

        val xtreamIncremental = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/XtreamIncrementalIndexExecutor.kt"
        ).readText()
        assertThat(xtreamIncremental).contains("suspend fun processSummary(")
        assertThat(xtreamIncremental).contains("private suspend fun streamFullSummary(")
        assertThat(xtreamIncremental).contains("shouldAttemptFullStream")

        val publication = repositoryRoot.resolve(
            "data/src/main/java/com/streamvault/data/sync/SyncStatusPublicationCoordinator.kt"
        ).readText()
        assertThat(publication).contains("suspend fun updateSummaryMetadata(")
        assertThat(publication).contains("suspend fun markMovieIndexRebuildAttempt(")
        assertThat(publication).contains("fun beginStateSession(")
        assertThat(publication).contains("fun finishProgressSession(")
    }

    @Test
    fun `legacy provider interface cannot return`() {
        val violations = productionKotlinFiles()
            .filter { path -> path.readText().contains("IptvProvider") }
            .map { it.repoRelativePath() }

        assertThat(violations).isEmpty()
    }

    private fun productionKotlinFiles(): List<Path> =
        listOf("domain/src/main", "data/src/main", "app/src/main")
            .flatMap { relative ->
                Files.walk(repositoryRoot.resolve(relative)).use { paths ->
                    paths.filter { path -> !path.isDirectory() && path.extension == "kt" }.toList()
                }
            }

    private fun Path.repoRelativePath(): String =
        repositoryRoot.relativize(this).invariantSeparatorsPathString

    private fun Path.readText(): String = String(Files.readAllBytes(this), Charsets.UTF_8)

    private data class DependencyBudget(
        val path: String,
        val className: String,
        val maxConstructorDependencies: Int
    )

    private fun constructorDependencyCount(source: String, className: String): Int {
        val classStart = source.indexOf("class $className")
        require(classStart >= 0) { "Class $className not found" }
        val openParen = source.indexOf("constructor(", classStart)
            .takeIf { it >= 0 }
            ?.plus("constructor".length)
            ?: source.indexOf('(', classStart)
        var depth = 0
        var closeParen = -1
        for (index in openParen until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        closeParen = index
                        break
                    }
                }
            }
        }
        require(closeParen > openParen) { "Constructor for $className is not balanced" }
        return source.substring(openParen + 1, closeParen)
            .splitTopLevel(',')
            .count { it.isNotBlank() }
    }

    private fun String.splitTopLevel(delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var parenDepth = 0
        var angleDepth = 0
        for (index in indices) {
            when (this[index]) {
                '(' -> parenDepth += 1
                ')' -> parenDepth -= 1
                '<' -> angleDepth += 1
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                delimiter -> if (parenDepth == 0 && angleDepth == 0) {
                    parts += substring(start, index)
                    start = index + 1
                }
            }
        }
        parts += substring(start)
        return parts
    }
}
