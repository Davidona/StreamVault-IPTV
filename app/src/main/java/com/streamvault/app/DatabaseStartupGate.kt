package com.streamvault.app

import android.content.Context
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.data.local.StreamVaultDatabase
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DatabaseStartupState {
    data object Opening : DatabaseStartupState
    data object Ready : DatabaseStartupState
    data class Failed(
        val errorType: String,
        val userMessage: String = "The local database could not be upgraded. Your data was not deleted."
    ) : DatabaseStartupState
}

internal class DatabaseStartupGate(
    private val onFailure: (Throwable) -> Unit = {},
    private val openDatabase: suspend () -> Unit
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<DatabaseStartupState>(DatabaseStartupState.Opening)
    val state: StateFlow<DatabaseStartupState> = mutableState.asStateFlow()

    fun start(scope: CoroutineScope): Job = scope.launch { open() }

    suspend fun open() {
        mutex.withLock {
            if (mutableState.value == DatabaseStartupState.Ready) return
            mutableState.value = DatabaseStartupState.Opening
            try {
                openDatabase()
                mutableState.value = DatabaseStartupState.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                runCatching { onFailure(error) }
                mutableState.value = DatabaseStartupState.Failed(
                    errorType = error.javaClass.simpleName.ifBlank { "DatabaseOpenError" }
                )
            }
        }
    }
}

@Singleton
class DatabaseStartupCoordinator @Inject constructor(
    database: Lazy<StreamVaultDatabase>,
    @ApplicationContext context: Context
) {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = DatabaseStartupGate(
        openDatabase = {
            withContext(Dispatchers.IO) {
                database.get().openHelper.writableDatabase
            }
        },
        onFailure = { error ->
            CrashReportStore.recordHandledFailure(context, "database-startup", error)
        }
    )

    val state: StateFlow<DatabaseStartupState> = gate.state

    fun start(): Job = gate.start(startupScope)

    suspend fun open() = gate.open()
}

internal data class StartupTask(
    val name: String,
    val action: suspend () -> Unit
)

internal suspend fun runContainedStartupTasks(
    tasks: List<StartupTask>,
    onFailure: (String, Throwable) -> Unit
) = supervisorScope {
    tasks.forEach { task ->
        launch {
            try {
                task.action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(task.name, error)
            }
        }
    }
}
