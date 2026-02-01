package io.runwork.bundle.updater.download

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
