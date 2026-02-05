package io.runwork.bundle.updater.download

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.storage.ContentAddressableStore

/**
 * Decides the optimal download strategy for a bundle update.
 */
object UpdateDecider {

    /**
     * Estimated overhead per HTTP request in bytes.
     *
     * This accounts for:
     * - TCP connection setup (if not reused): ~1-3KB
     * - TLS handshake overhead: ~5-10KB
     * - HTTP request/response headers: ~1-2KB
     * - Latency cost (time that could have been spent downloading)
     *
     * We use 50KB as a conservative estimate that favors fewer requests.
     * This helps avoid the "many small files" problem where HTTP overhead
     * dominates actual data transfer time.
     */
    private const val HTTP_REQUEST_OVERHEAD_BYTES = 50_000L

    /**
     * Decide whether to download the full bundle ZIP or individual files.
     *
     * The decision is based on effective download cost:
     * - Full bundle: totalSize bytes (for the platform-specific bundle)
     * - Incremental: sum of file sizes + HTTP overhead per file
     *
     * This accounts for the latency cost of many small HTTP requests,
     * which can make downloading many small files slower than one large archive
     * even when the total bytes are smaller.
     *
     * @param manifest The new bundle manifest
     * @param platform The target platform to filter files for
     * @param contentStore Content store to check existing files
     * @return Download strategy with list of files to download
     */
    suspend fun decide(
        manifest: BundleManifest,
        platform: Platform,
        contentStore: ContentAddressableStore
    ): DownloadStrategy {
        // Get files for this platform
        val platformFiles = manifest.filesForPlatform(platform)

        // Find files that are not in the CAS
        val missingFiles = platformFiles.filter { file ->
            !contentStore.contains(file.hash)
        }

        // If no files are missing, no download needed
        if (missingFiles.isEmpty()) {
            return DownloadStrategy.NoDownloadNeeded
        }

        // Calculate sizes
        val incrementalDataSize = missingFiles.sumOf { it.size }
        val incrementalOverhead = missingFiles.size * HTTP_REQUEST_OVERHEAD_BYTES
        val effectiveIncrementalSize = incrementalDataSize + incrementalOverhead

        // Full bundle size for this platform
        val fullSize = manifest.sizeForPlatform(platform) ?: platformFiles.sumOf { it.size }

        // Choose the strategy with lower effective cost
        return if (fullSize <= effectiveIncrementalSize) {
            DownloadStrategy.FullBundle(
                totalSize = fullSize,
                fileCount = platformFiles.size
            )
        } else {
            DownloadStrategy.Incremental(
                files = missingFiles,
                totalSize = incrementalDataSize
            )
        }
    }
}

/**
 * Download strategy for a bundle update.
 */
sealed class DownloadStrategy {
    /** No download needed - all files are already in CAS */
    data object NoDownloadNeeded : DownloadStrategy()

    /** Download the full bundle ZIP */
    data class FullBundle(
        val totalSize: Long,
        val fileCount: Int,
    ) : DownloadStrategy()

    /** Download only the missing files */
    data class Incremental(
        val files: List<BundleFile>,
        val totalSize: Long,
    ) : DownloadStrategy()
}
