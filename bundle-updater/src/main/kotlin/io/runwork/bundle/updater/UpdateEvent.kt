package io.runwork.bundle.updater

import io.runwork.bundle.updater.download.DownloadProgress
import io.runwork.bundle.updater.result.CleanupResult

/**
 * Events emitted by the [BundleUpdater] when running as a background service.
 */
sealed class UpdateEvent {
    /** Currently checking for updates */
    data object Checking : UpdateEvent()

    /** A new update is available */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateEvent()

    /** Download progress update */
    data class Downloading(val progress: DownloadProgress) : UpdateEvent()

    /** Update downloaded and ready to apply */
    data class UpdateReady(val newBuildNumber: Long) : UpdateEvent()

    /** An error occurred (may be recoverable) */
    data class Error(val error: UpdateError) : UpdateEvent()

    /** Cleanup cycle completed */
    data class CleanupComplete(val result: CleanupResult) : UpdateEvent()

    /** Currently up to date, waiting for next check */
    data object UpToDate : UpdateEvent()
}

/**
 * Information about an available update.
 */
data class UpdateInfo(
    /** Build number of the new version */
    val newBuildNumber: Long,

    /** Build number of the currently running version */
    val currentBuildNumber: Long,

    /** Estimated download size in bytes */
    val downloadSize: Long,

    /** Whether the update will use incremental downloading */
    val isIncremental: Boolean,
)

/**
 * Error that occurred during update operations.
 */
data class UpdateError(
    /** Human-readable error message */
    val message: String,

    /** Underlying cause, if any */
    val cause: Throwable? = null,

    /** Whether this error is recoverable (e.g., network timeout vs signature failure) */
    val isRecoverable: Boolean = true,
)
