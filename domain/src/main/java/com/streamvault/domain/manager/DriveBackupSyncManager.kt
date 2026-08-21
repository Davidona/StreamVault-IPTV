package com.streamvault.domain.manager

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

/**
 * Backs up and restores the local configuration (providers, favorites, groups,
 * preferences) to a private Google Drive `appDataFolder`. The folder is owned
 * by the app on Drive — invisible to the user in the standard Drive UI — and
 * is wiped automatically by Google when the app is uninstalled.
 *
 * The payload reuses the existing [BackupManager] JSON format, so a Drive push
 * is functionally equivalent to a local `Export to file` and a Drive pull
 * feeds the same `Inspect/Import` flow (with preview + conflict resolution).
 *
 * All operations are suspend and return [Result]; UI layers should never see
 * an exception escape.
 */
interface DriveBackupSyncManager {

    /** Last published auth state. UI observes this to render the sign-in section. */
    val authState: Flow<DriveAuthState>

    /** Last sync timestamps + outcome. UI observes this to show "Last push" / "Last pull". */
    val syncStatus: Flow<DriveSyncStatus>

    /**
     * Builds the platform-specific Sign-In intent the caller must launch with an
     * `ActivityResultLauncher`. The resulting `Intent` data is forwarded back to
     * [completeSignIn].
     *
     * Returns [Result.Error] if Google Play Services is missing.
     */
    suspend fun beginSignIn(): Result<DriveSignInRequest>

    /**
     * Completes the OAuth flow with the data returned by the launched Sign-In
     * intent. On success, [authState] emits [DriveAuthState.SignedIn].
     */
    suspend fun completeSignIn(signInData: Any?): Result<DriveAccount>

    /** Revokes scopes and clears cached account locally. */
    suspend fun signOut(): Result<Unit>

    /**
     * Exports the current configuration and its matching credentials snapshot
     * into one versioned Drive artifact. Keeping both payloads in one file
     * prevents a backup and credentials upload from representing different
     * local states.
     */
    suspend fun pushBackup(
        credentials: List<ProviderCredentials> = emptyList(),
    ): Result<DriveSyncStatus>

    /** Lists available Drive snapshots, newest first, for user selection. */
    suspend fun listBackups(): Result<List<DriveBackupSnapshot>>

    /** Deletes one backup snapshot after the caller has confirmed the action. */
    suspend fun deleteBackup(snapshotId: String): Result<Unit>

    /**
     * Downloads a selected backup from Drive `appDataFolder` to a temporary
     * local file, then hands the URI back to the caller — typically wired into
     * the existing [BackupManager.inspectBackup] flow so the preview + conflict
     * resolution UI is reused unchanged.
     *
     * If [snapshotId] is omitted, the newest available snapshot is selected.
     * Returns [Result.Error] with code [DriveSyncError.NO_REMOTE_BACKUP] if
     * nothing has been pushed yet or the requested snapshot no longer exists.
     */
    suspend fun pullBackup(snapshotId: String? = null): Result<DriveBackupArtifact>

    /**
     * Legacy compatibility upload for the provider credentials list. New UI
     * pushes pass credentials to [pushBackup] so they are bundled with the
     * matching backup snapshot; this method remains only for older callers.
     *
     * Pure overwrite (last-write-wins). Matching at pull time uses
     * `(serverUrl, username)` so provider id reshuffling on import does
     * not invalidate the restore.
     */
    suspend fun pushCredentials(credentials: List<ProviderCredentials>): Result<Unit>

    /**
     * Legacy compatibility download for `streamvault_credentials.json` from
     * Drive `appDataFolder`. New bundle pulls expose credentials through
     * [DriveBackupArtifact.credentials]. Returns an empty list if the legacy
     * file is absent.
     */
    suspend fun pullCredentials(): Result<List<ProviderCredentials>>
}

/**
 * Cleartext credentials for a single provider, transported alongside the main
 * backup JSON. Drive storage relies on the `drive.appdata` scope + Google
 * account ACL for confidentiality. Matching uses `(serverUrl, username, providerType)` when available.
 */
data class ProviderCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    val providerType: com.streamvault.domain.model.ProviderType? = null,
)

/** Public Sign-In state for the UI. */
sealed class DriveAuthState {
    /** Not signed in (initial, after sign-out, after explicit revoke). */
    data object SignedOut : DriveAuthState()
    /** Sign-In is mid-flight (intent launched but not completed yet). */
    data object Pending : DriveAuthState()
    /** Active account. `email` may be null on platforms where the API hides it. */
    data class SignedIn(val account: DriveAccount) : DriveAuthState()
}

data class DriveAccount(
    val email: String?,
    val displayName: String?,
)

data class DriveSyncStatus(
    val lastPushAtMs: Long? = null,
    val lastPullAtMs: Long? = null,
    val lastErrorMessage: String? = null,
)

/** Carries the intent the UI must launch. Kept opaque (`Any?`) for platform independence. */
data class DriveSignInRequest(val intent: Any?)

/** A user-visible backup snapshot stored in Drive `appDataFolder`. */
data class DriveBackupSnapshot(
    val id: String,
    val fileName: String,
    val modifiedAtMs: Long? = null,
    val sizeBytes: Long = 0L,
    val isBundle: Boolean = true,
)

/** Local artifact produced by [DriveBackupSyncManager.pullBackup]. */
data class DriveBackupArtifact(
    /** SAF-compatible `file://` URI string the existing [BackupManager.inspectBackup] can read. */
    val localUriString: String,
    val sizeBytes: Long,
    /** Credentials embedded in the versioned bundle; null means a legacy backup file. */
    val credentials: List<ProviderCredentials>? = null,
)

/** Stable identifiers UI may match against to localize messages. */
object DriveSyncError {
    const val PLAY_SERVICES_UNAVAILABLE = "drive_play_services_unavailable"
    const val NOT_SIGNED_IN = "drive_not_signed_in"
    const val NO_REMOTE_BACKUP = "drive_no_remote_backup"
    const val NETWORK = "drive_network"
    const val AUTH_FAILED = "drive_auth_failed"
    const val EXPORT_FAILED = "drive_export_failed"
    const val IMPORT_FAILED = "drive_import_failed"
    const val PAYLOAD_TOO_LARGE = "drive_payload_too_large"
}
