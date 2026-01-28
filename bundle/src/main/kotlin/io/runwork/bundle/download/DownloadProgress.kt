package io.runwork.bundle.download

/**
 * Progress information for bundle downloads.
 */
data class DownloadProgress(
    /** Total bytes downloaded so far */
    val bytesDownloaded: Long,

    /** Total bytes to download */
    val totalBytes: Long,

    /** Current file being downloaded (null for full bundle zip) */
    val currentFile: String?,

    /** Number of files completed (for incremental downloads) */
    val filesCompleted: Int,

    /** Total number of files to download (for incremental downloads) */
    val totalFiles: Int,
) {
    /** Progress as a percentage (0.0 to 1.0) */
    val percentComplete: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f

    /** Progress as an integer percentage (0 to 100) */
    val percentCompleteInt: Int
        get() = (percentComplete * 100).toInt()
}

/**
 * Result of a download operation.
 */
sealed class DownloadResult {
    /** Download completed successfully */
    data class Success(val buildNumber: Long) : DownloadResult()

    /** Download failed */
    data class Failure(val error: String, val cause: Throwable? = null) : DownloadResult()

    /** Download was cancelled */
    data object Cancelled : DownloadResult()
}

/**
 * Exception thrown when a download fails.
 */
class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
