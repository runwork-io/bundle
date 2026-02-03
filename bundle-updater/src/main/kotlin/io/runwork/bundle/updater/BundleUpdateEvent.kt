package io.runwork.bundle.updater

import io.runwork.bundle.updater.download.DownloadProgress
import io.runwork.bundle.updater.result.CleanupResult
import java.time.Instant

/**
 * Events emitted by the [BundleUpdater] when running as a background service.
 */
sealed class BundleUpdateEvent {
    /** Currently checking for updates */
    data object Checking : BundleUpdateEvent()

    /** A new update is available */
    data class UpdateAvailable(val info: UpdateInfo) : BundleUpdateEvent()

    /** Download progress update */
    data class Downloading(val progress: DownloadProgress) : BundleUpdateEvent()

    /** Update downloaded and ready to apply */
    data class UpdateReady(val newBuildNumber: Long) : BundleUpdateEvent()

    /** An error occurred (may be recoverable) */
    data class Error(val error: UpdateError) : BundleUpdateEvent()

    /** Retry is scheduled after a transient error */
    data class BackingOff(
        /** Which retry attempt this is (1 = first retry) */
        val retryNumber: Int,

        /** How long to wait before the next retry, in seconds */
        val delaySeconds: Long,

        /** When the next retry will occur */
        val nextRetryTime: Instant,

        /** The error that triggered this retry */
        val error: UpdateError,
    ) : BundleUpdateEvent()

    /** Cleanup cycle completed */
    data class CleanupComplete(val result: CleanupResult) : BundleUpdateEvent()

    /** Currently up to date, waiting for next check */
    data object UpToDate : BundleUpdateEvent()
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
