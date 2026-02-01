package io.runwork.bundle.updater.result

/**
 * Result of a download operation.
 */
sealed class DownloadResult {
    /** Download completed successfully */
    data class Success(val buildNumber: Long) : DownloadResult()

    /** No download needed - already up to date */
    data object AlreadyUpToDate : DownloadResult()

    /** Download failed */
    data class Failure(val error: String, val cause: Throwable? = null) : DownloadResult()

    /** Download was cancelled */
    data object Cancelled : DownloadResult()
}

/**
 * Exception thrown when a download fails.
 */
class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
