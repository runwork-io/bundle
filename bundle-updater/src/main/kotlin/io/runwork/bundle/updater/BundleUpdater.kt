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
import java.io.Closeable

/**
 * Main entry point for bundle updates.
 *
 * **For shell applications**: Use [io.runwork.bundle.bootstrap.BundleBootstrap.validateAndLaunch] instead of
 * creating a `BundleUpdater` directly. `BundleBootstrap.validateAndLaunch()` handles the full lifecycle
 * (validate → download → launch) and prevents storage conflicts between shell and bundle updaters.
 *
 * **For bundle self-updates**: Create a `BundleUpdater` using [BundleUpdaterConfig.fromLaunchConfig]
 * and call [runInBackground] to begin periodic update checking.
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
            val (manifest, rawJson) = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> Pair(result.manifest, result.rawJson)
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
                finalizeUpdate(manifest, rawJson)
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
        data class Success(val manifest: BundleManifest, val rawJson: String) : ManifestFetchResult()
        data class NetworkError(val message: String, val cause: Throwable) : ManifestFetchResult()
        data class SignatureFailure(val message: String) : ManifestFetchResult()
        data class PlatformMismatch(val expected: String, val actual: String) : ManifestFetchResult()
    }

    private suspend fun fetchAndVerifyManifest(): ManifestFetchResult {
        return try {
            val fetched = downloadManager.fetchManifest()

            // Verify signature against raw JSON (forward-compatible with unknown fields)
            if (!signatureVerifier.verifyManifestJson(fetched.rawJson)) {
                return ManifestFetchResult.SignatureFailure("Manifest signature verification failed")
            }

            // Verify platform is supported
            if (!fetched.manifest.supportsPlatform(config.platform)) {
                val supported = fetched.manifest.zips.keys.sorted().joinToString(", ")
                return ManifestFetchResult.PlatformMismatch(
                    expected = config.platform.toString(),
                    actual = supported
                )
            }

            ManifestFetchResult.Success(fetched.manifest, fetched.rawJson)
        } catch (e: Exception) {
            ManifestFetchResult.NetworkError(e.message ?: "Failed to fetch manifest", e)
        }
    }

    private suspend fun finalizeUpdate(manifest: BundleManifest, rawJson: String) {
        storageManager.withWriteScope { scope ->
            // Prepare version directory (hard links from CAS for this platform's files)
            scope.prepareVersion(manifest, config.platform)
            // Save the raw JSON directly to preserve unknown fields for forward-compatible
            // signature verification on subsequent bootstrap validations.
            scope.saveManifest(rawJson)
        }
    }

    private suspend fun ProducerScope<BundleUpdateEvent>.checkAndDownloadUpdate() {
        try {
            send(BundleUpdateEvent.Checking)

            val (manifest, rawJson) = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> Pair(result.manifest, result.rawJson)
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
                tryCleanup(manifest)?.let { send(BundleUpdateEvent.CleanupComplete(it)) }
                return
            }

            // Determine download strategy
            val strategy = UpdateDecider.decide(manifest, config.platform, downloadManager.contentStore)

            if (strategy is DownloadStrategy.NoDownloadNeeded) {
                // Files already in CAS, just finalize
                finalizeUpdate(manifest, rawJson)
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
            val result = downloadManager.downloadBundle(manifest, strategy) { progress ->
                trySend(BundleUpdateEvent.Downloading(progress))
            }

            when (result) {
                is DownloadResult.Success -> {
                    finalizeUpdate(manifest, rawJson)
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

    private suspend fun tryCleanup(manifest: BundleManifest): CleanupResult? {
        return try {
            cleanupManager.cleanup(manifest)
        } catch (e: Exception) {
            // Cleanup failures are non-fatal
            null
        }
    }
}
