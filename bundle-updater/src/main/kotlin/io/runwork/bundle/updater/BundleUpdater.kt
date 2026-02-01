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
import io.runwork.bundle.updater.result.UpdateCheckResult
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Main entry point for bundle updates.
 *
 * Provides two usage modes:
 * 1. **One-shot** (shell initial download): Call [downloadLatest] to download the latest bundle
 * 2. **Background service** (bundle self-updates): Call [start] to begin periodic update checking
 *
 * Critical behaviors:
 * - Downgrade prevention: Only downloads manifests with buildNumber > current
 * - Concurrency: Storage operations are mutex-protected
 * - Update-first cleanup: Never deletes files until fully up-to-date
 */
class BundleUpdater(
    private val config: BundleUpdaterConfig
) : Closeable {
    private val storageManager = StorageManager(config.appDataDir)
    private val cleanupManager = CleanupManager(storageManager, config.appDataDir)
    private val downloadManager = DownloadManager(config.baseUrl, storageManager)
    private val signatureVerifier = SignatureVerifier(config.publicKey)
    private val json = Json { ignoreUnknownKeys = true }

    private val currentState = AtomicReference<BundleUpdaterState>(BundleUpdaterState.Idle)

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
            val currentBuild = storageManager.getCurrentVersion() ?: 0
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
     * Start background update checking.
     *
     * Returns a Flow that emits update events. The updater runs continuously,
     * checking for updates at the configured interval and running cleanup cycles.
     *
     * @return Flow of update events
     */
    fun start(): Flow<BundleUpdateEvent> = channelFlow {
        // Initial cleanup on startup (only if up-to-date)
        tryCleanupIfUpToDate()?.let { send(BundleUpdateEvent.CleanupComplete(it)) }

        while (isActive) {
            checkAndDownloadUpdate()
            delay(config.checkInterval)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Manually trigger an update check.
     *
     * @return Result of the update check
     */
    suspend fun checkNow(): UpdateCheckResult {
        return try {
            currentState.set(BundleUpdaterState.Checking)

            val manifest = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> result.manifest
                is ManifestFetchResult.NetworkError -> {
                    val error = UpdateError(result.message, result.cause)
                    currentState.set(BundleUpdaterState.Error(error))
                    return UpdateCheckResult.Failed(result.message, result.cause)
                }
                is ManifestFetchResult.SignatureFailure -> {
                    val error = UpdateError(result.message)
                    currentState.set(BundleUpdaterState.Error(error))
                    return UpdateCheckResult.Failed(result.message)
                }
                is ManifestFetchResult.PlatformMismatch -> {
                    val message = "Platform mismatch: expected ${result.expected}, got ${result.actual}"
                    val error = UpdateError(message)
                    currentState.set(BundleUpdaterState.Error(error))
                    return UpdateCheckResult.Failed(message)
                }
            }

            // Check for downgrade
            if (manifest.buildNumber <= config.currentBuildNumber) {
                currentState.set(BundleUpdaterState.Idle)
                tryCleanupIfUpToDate()
                return UpdateCheckResult.UpToDate
            }

            // Determine download strategy to get size info
            val strategy = UpdateDecider.decide(manifest, downloadManager.contentStore)

            val info = when (strategy) {
                is DownloadStrategy.NoDownloadNeeded -> {
                    currentState.set(BundleUpdaterState.Idle)
                    return UpdateCheckResult.UpToDate
                }
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
            }

            currentState.set(BundleUpdaterState.Idle)
            UpdateCheckResult.UpdateAvailable(info)
        } catch (e: Exception) {
            val error = UpdateError(e.message ?: "Check failed", e)
            currentState.set(BundleUpdaterState.Error(error))
            UpdateCheckResult.Failed(e.message ?: "Check failed", e)
        }
    }

    /**
     * Get current state.
     */
    fun getState(): BundleUpdaterState = currentState.get()

    // ============ SHARED API ============

    /**
     * Manually trigger cleanup of old versions and orphaned CAS files.
     *
     * IMPORTANT: This should only be called when fully up-to-date.
     * The method verifies this by checking that no update is available.
     *
     * @return Cleanup result, or null if an update is pending
     */
    suspend fun cleanup(): CleanupResult? {
        return try {
            // Fetch manifest to check if we're up-to-date
            val manifest = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> result.manifest
                is ManifestFetchResult.NetworkError,
                is ManifestFetchResult.SignatureFailure,
                is ManifestFetchResult.PlatformMismatch -> return null
            }

            val currentBuild = storageManager.getCurrentVersion() ?: return null

            // If update available, DO NOT cleanup
            if (manifest.buildNumber > currentBuild) {
                return null
            }

            // We're up to date - safe to cleanup
            cleanupManager.cleanup(manifest)
        } catch (e: Exception) {
            // On error, don't cleanup (safe default)
            null
        }
    }

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

            // Verify platform matches
            if (manifest.platform != config.platform.toString()) {
                return ManifestFetchResult.PlatformMismatch(
                    expected = config.platform.toString(),
                    actual = manifest.platform
                )
            }

            ManifestFetchResult.Success(manifest)
        } catch (e: Exception) {
            ManifestFetchResult.NetworkError(e.message ?: "Failed to fetch manifest", e)
        }
    }

    private suspend fun finalizeUpdate(manifest: BundleManifest) {
        storageManager.withStorageLock {
            // Prepare version directory (hard links from CAS)
            storageManager.prepareVersion(manifest)

            // Save manifest
            storageManager.saveManifest(json.encodeToString(manifest))

            // Update current pointer
            storageManager.setCurrentVersion(manifest.buildNumber)
        }
    }

    private suspend fun ProducerScope<BundleUpdateEvent>.checkAndDownloadUpdate() {
        try {
            currentState.set(BundleUpdaterState.Checking)
            send(BundleUpdateEvent.Checking)

            val manifest = when (val result = fetchAndVerifyManifest()) {
                is ManifestFetchResult.Success -> result.manifest
                is ManifestFetchResult.NetworkError -> {
                    val error = UpdateError(result.message, result.cause)
                    currentState.set(BundleUpdaterState.Error(error))
                    send(BundleUpdateEvent.Error(error))
                    return
                }
                is ManifestFetchResult.SignatureFailure -> {
                    val error = UpdateError(result.message)
                    currentState.set(BundleUpdaterState.Error(error))
                    send(BundleUpdateEvent.Error(error))
                    return
                }
                is ManifestFetchResult.PlatformMismatch -> {
                    val message = "Platform mismatch: expected ${result.expected}, got ${result.actual}"
                    val error = UpdateError(message)
                    currentState.set(BundleUpdaterState.Error(error))
                    send(BundleUpdateEvent.Error(error))
                    return
                }
            }

            // Check for downgrade
            if (manifest.buildNumber <= config.currentBuildNumber) {
                currentState.set(BundleUpdaterState.Idle)
                send(BundleUpdateEvent.UpToDate)
                // Up to date - run cleanup
                tryCleanupIfUpToDate()?.let { send(BundleUpdateEvent.CleanupComplete(it)) }
                return
            }

            // Determine download strategy
            val strategy = UpdateDecider.decide(manifest, downloadManager.contentStore)

            if (strategy is DownloadStrategy.NoDownloadNeeded) {
                // Files already in CAS, just finalize
                finalizeUpdate(manifest)
                currentState.set(BundleUpdaterState.Ready(manifest.buildNumber))
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
                currentState.set(BundleUpdaterState.Downloading(progress))
                trySend(BundleUpdateEvent.Downloading(progress))
            }

            when (result) {
                is DownloadResult.Success -> {
                    finalizeUpdate(manifest)
                    currentState.set(BundleUpdaterState.Ready(manifest.buildNumber))
                    send(BundleUpdateEvent.UpdateReady(manifest.buildNumber))
                }
                is DownloadResult.AlreadyUpToDate -> {
                    currentState.set(BundleUpdaterState.Idle)
                    send(BundleUpdateEvent.UpToDate)
                }
                is DownloadResult.Failure -> {
                    val error = UpdateError(result.error, result.cause)
                    currentState.set(BundleUpdaterState.Error(error))
                    send(BundleUpdateEvent.Error(error))
                }
                is DownloadResult.Cancelled -> {
                    currentState.set(BundleUpdaterState.Idle)
                }
            }
        } catch (e: Exception) {
            val error = UpdateError(e.message ?: "Update failed", e)
            currentState.set(BundleUpdaterState.Error(error))
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
            val currentBuild = storageManager.getCurrentVersion() ?: return null

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
