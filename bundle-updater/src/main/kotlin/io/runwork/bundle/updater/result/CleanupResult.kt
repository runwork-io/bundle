package io.runwork.bundle.updater.result

/**
 * Result of a cleanup operation.
 */
data class CleanupResult(
    /** Build numbers of versions that were removed */
    val versionsRemoved: List<Long>,

    /** Number of orphaned CAS files that were deleted */
    val casFilesRemoved: Int,

    /** Total disk space reclaimed in bytes */
    val bytesFreed: Long,
)
