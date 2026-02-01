package io.runwork.bundle.updater.result

import io.runwork.bundle.updater.UpdateInfo

/**
 * Result of checking for updates.
 */
sealed class UpdateCheckResult {
    /** No update available - already on the latest version */
    data object UpToDate : UpdateCheckResult()

    /** An update is available */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()

    /** Failed to check for updates */
    data class Failed(val error: String, val cause: Throwable? = null) : UpdateCheckResult()
}
