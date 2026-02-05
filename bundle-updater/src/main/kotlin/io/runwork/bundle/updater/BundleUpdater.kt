package io.runwork.bundle.updater

import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.verification.SignatureVerifier
import io.runwork.bundle.updater.download.DownloadManager
import io.runwork.bundle.updater.download.DownloadProgress
import io.runwork.bundle.updater.download.DownloadStrategy
import io.runwork.bundle.updater.download.UpdateDecider
import io.runwork.bundle.updater.result.CleanupResult
import io.runwork.bundle.updater.result.DownloadException
import io.runwork.bundle.updater.result.DownloadResult
import io.runwork.bundle.updater.storage.CleanupManager
import io.runwork.bundle.updater.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable

/**
 * Main entry point for bundle updates.
 *
 * Provides two usage modes:
 * 1. **One-shot** (shell initial download): Call [downloadLatest] to download the latest bundle
 * 2. **Background service** (bundle self-updates): Call [runInBackground] to begin periodic update checking
 *
 * Critical behaviors:
 * - Downgrade prevention: Only downloads manifests with buildNumber > current
 * - Concurrency: Storage operations are mutex-protected
 * - Update-first cleanup: Never deletes files until fully up-to-date
 */
class BundleUpdater(
    private val config: BundleUpdaterConfig
) : Closeable {
    private val storageManager = StorageManager(config.bundleDir)
    private val cleanupManager = CleanupManager(storageManager, config.bundleDir, config.platform)
    private val downloadManager = DownloadManager(config.baseUrl, storageManager, config.platform)
    private val signatureVerifier = SignatureVerifier(config.publicKey)
    private val json = Json { ignoreUnknownKeys = true }

    // ============ ONE-SHOT API (used by shell for initial download) ============

    /**
     * Download the latest bundle.
     *
     * Used by shell when no bundle exists or when forcing an update.
     * Returns when download is complete.
     *
     * @param onProgress Called with progress updates during download
     * @return Download result
     */
    suspend fun downloadLatest(
        onProgress: (DownloadProgress) -> Unit = {}
    ): DownloadResult {
        return try {
            // Fetch and verify manifest
            val manifest = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> result.manifest
                is ManifestFetchResult.NetworkError ->
                    return DownloadResult.Failure(result.message, result.cause)
                is ManifestFetchResult.SignatureFailure ->
                    return DownloadResult.Failure(result.message)
                is ManifestFetchResult.PlatformMismatch ->
                    return DownloadResult.Failure(
                        "Platform mismatch: expected ${result.expected}, got ${result.actual}"
                    )
            }

            // Check for downgrade (buildNumber must be > current)
            val currentBuild = storageManager.getCurrentBuildNumber() ?: 0
            if (manifest.buildNumber <= currentBuild) {
                return DownloadResult.AlreadyUpToDate
            }

            // Download the bundle
            val result = downloadManager.downloadBundle(manifest) { progress ->
                onProgress(progress)
            }

            if (result is DownloadResult.Success) {
                // Prepare version directory and update current pointer
                finalizeUpdate(manifest)
            }

            result
        } catch (e: DownloadException) {
            DownloadResult.Failure(e.message ?: "Download failed", e)
        } catch (e: Exception) {
            DownloadResult.Failure("Unexpected error: ${e.message}", e)
        }
    }

    // ============ BACKGROUND SERVICE API (used by bundle for self-updates) ============

    /**
     * Run background update checking.
     *
     * Returns a Flow that emits update events. The updater runs continuously,
     * checking for updates at the configured interval and running cleanup cycles.
     *
     * @return Flow of update events
     */
    fun runInBackground(): Flow<BundleUpdateEvent> = channelFlow {
        // Initial cleanup on startup (only if up-to-date)
        tryCleanupIfUpToDate()?.let { send(BundleUpdateEvent.CleanupComplete(it)) }

        while (isActive) {
            checkAndDownloadUpdate()
            delay(config.checkInterval)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Close the updater and release resources.
     */
    override fun close() {
        downloadManager.close()
    }

    // ============ INTERNAL ============

    private sealed class ManifestFetchResult {
        data class Success(val manifest: BundleManifest) : ManifestFetchResult()
        data class NetworkError(val message: String, val cause: Throwable) : ManifestFetchResult()
        data class SignatureFailure(val message: String) : ManifestFetchResult()
        data class PlatformMismatch(val expected: String, val actual: String) : ManifestFetchResult()
    }

    private suspend fun fetchAndVerifyManifest(): ManifestFetchResult {
        return try {
            val manifest = downloadManager.fetchManifest()

            // Verify signature
            if (!signatureVerifier.verifyManifest(manifest)) {
                return ManifestFetchResult.SignatureFailure("Manifest signature verification failed")
            }

            // Verify platform is supported
            if (!manifest.supportsPlatform(config.platform)) {
                val supported = manifest.zips.keys.sorted().joinToString(", ")
                return ManifestFetchResult.PlatformMismatch(
                    expected = config.platform.toString(),
                    actual = supported
                )
            }

            ManifestFetchResult.Success(manifest)
        } catch (e: Exception) {
            ManifestFetchResult.NetworkError(e.message ?: "Failed to fetch manifest", e)
        }
    }

    private suspend fun finalizeUpdate(manifest: BundleManifest) {
        // Prepare version directory (hard links from CAS for this platform's files)
        // Note: prepareVersion and saveManifest each acquire the storage lock internally,
        // so we don't wrap them in an outer lock to avoid deadlock (Mutex is not reentrant).
        storageManager.prepareVersion(manifest, config.platform)
        storageManager.saveManifest(json.encodeToString(manifest))
    }

    private suspend fun ProducerScope<BundleUpdateEvent>.checkAndDownloadUpdate() {
        try {
            send(BundleUpdateEvent.Checking)

            val manifest = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> result.manifest
                is ManifestFetchResult.NetworkError -> {
                    val error = UpdateError(result.message, result.cause)
                    send(BundleUpdateEvent.Error(error))
                    return
                }
                is ManifestFetchResult.SignatureFailure -> {
                    val error = UpdateError(result.message)
                    send(BundleUpdateEvent.Error(error))
                    return
                }
                is ManifestFetchResult.PlatformMismatch -> {
                    val message = "Platform mismatch: expected ${result.expected}, got ${result.actual}"
                    val error = UpdateError(message)
                    send(BundleUpdateEvent.Error(error))
                    return
                }
            }

            // Check for downgrade
            if (manifest.buildNumber <= config.currentBuildNumber) {
                send(BundleUpdateEvent.UpToDate)
                // Up to date - run cleanup
                tryCleanupIfUpToDate()?.let { send(BundleUpdateEvent.CleanupComplete(it)) }
                return
            }

            // Determine download strategy
            val strategy = UpdateDecider.decide(manifest, config.platform, downloadManager.contentStore)

            if (strategy is DownloadStrategy.NoDownloadNeeded) {
                // Files already in CAS, just finalize
                finalizeUpdate(manifest)
                send(BundleUpdateEvent.UpdateReady(manifest.buildNumber))
                return
            }

            // Notify update available
            val info = when (strategy) {
                is DownloadStrategy.FullBundle -> UpdateInfo(
                    newBuildNumber = manifest.buildNumber,
                    currentBuildNumber = config.currentBuildNumber,
                    downloadSize = strategy.totalSize,
                    isIncremental = false,
                )
                is DownloadStrategy.Incremental -> UpdateInfo(
                    newBuildNumber = manifest.buildNumber,
                    currentBuildNumber = config.currentBuildNumber,
                    downloadSize = strategy.totalSize,
                    isIncremental = true,
                )
                is DownloadStrategy.NoDownloadNeeded -> return // Already handled above
            }
            send(BundleUpdateEvent.UpdateAvailable(info))

            // Download
            val result = downloadManager.downloadBundle(manifest) { progress ->
                trySend(BundleUpdateEvent.Downloading(progress))
            }

            when (result) {
                is DownloadResult.Success -> {
                    finalizeUpdate(manifest)
                    send(BundleUpdateEvent.UpdateReady(manifest.buildNumber))
                }
                is DownloadResult.AlreadyUpToDate -> {
                    send(BundleUpdateEvent.UpToDate)
                }
                is DownloadResult.Failure -> {
                    val error = UpdateError(result.error, result.cause)
                    send(BundleUpdateEvent.Error(error))
                }
                is DownloadResult.Cancelled -> {
                    // No event needed for cancelled downloads
                }
            }
        } catch (e: Exception) {
            val error = UpdateError(e.message ?: "Update failed", e)
            send(BundleUpdateEvent.Error(error))
        }
    }

    private suspend fun tryCleanupIfUpToDate(): CleanupResult? {
        return try {
            val manifest = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> result.manifest
                is ManifestFetchResult.NetworkError,
                is ManifestFetchResult.SignatureFailure,
                is ManifestFetchResult.PlatformMismatch -> return null
            }
            val currentBuild = storageManager.getCurrentBuildNumber() ?: return null

            // Only cleanup if up to date
            if (manifest.buildNumber <= currentBuild) {
                cleanupManager.cleanup(manifest)
            } else {
                null
            }
        } catch (e: Exception) {
            // Cleanup failures are non-fatal
            null
        }
    }
}
