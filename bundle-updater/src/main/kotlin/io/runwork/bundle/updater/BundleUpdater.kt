package io.runwork.bundle.updater

import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.verification.SignatureVerifier
import io.runwork.bundle.updater.download.DownloadManager
import io.runwork.bundle.updater.download.DownloadProgress
import io.runwork.bundle.updater.download.DownloadStrategy
import io.runwork.bundle.updater.download.UpdateDecider
import io.runwork.bundle.updater.result.CleanupResult
import io.runwork.bundle.updater.result.DownloadResult
import io.runwork.bundle.updater.result.UpdateCheckResult
import io.runwork.bundle.updater.retry.RetryExecutor
import io.runwork.bundle.updater.retry.isRecoverableError
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
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

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
 * - Retry with exponential backoff: Network errors trigger automatic retries
 */
class BundleUpdater(
    private val config: BundleUpdaterConfig,
    clock: Clock = Clock.systemUTC(),
    delayFunction: suspend (Duration) -> Unit = { delay(it) },
) : Closeable {
    private val storageManager = StorageManager(config.bundleDir)
    private val cleanupManager = CleanupManager(storageManager, config.bundleDir, config.platform)
    private val downloadManager = DownloadManager(config.baseUrl, storageManager, config.platform)
    private val signatureVerifier = SignatureVerifier(config.publicKey)
    private val json = Json { ignoreUnknownKeys = true }
    private val retryExecutor = RetryExecutor(config.retryConfig, clock, delayFunction)

    private val currentState = AtomicReference<BundleUpdaterState>(BundleUpdaterState.Idle)

    // ============ ONE-SHOT API (used by shell for initial download) ============

    /**
     * Download the latest bundle.
     *
     * Used by shell when no bundle exists or when forcing an update.
     * Returns a Flow that emits events during the download process, including
     * retry backoff events if network errors occur.
     *
     * The Flow completes after:
     * - Success: [BundleUpdateEvent.UpdateReady] is emitted
     * - Already up-to-date: [BundleUpdateEvent.UpToDate] is emitted
     * - Failure: [BundleUpdateEvent.Error] is emitted (after retries exhausted for recoverable errors)
     *
     * To cancel and retry immediately (resets retry count), cancel the Flow and call this method again.
     *
     * @return Flow of update events
     */
    fun downloadLatest(): Flow<BundleUpdateEvent> = channelFlow {
        currentState.set(BundleUpdaterState.Checking)
        send(BundleUpdateEvent.Checking)

        // Fetch and verify manifest with retry
        val manifestResult = retryExecutor.executeWithRetry(
            emit = { backoffEvent ->
                currentState.set(BundleUpdaterState.BackingOff(backoffEvent.retryNumber, backoffEvent.nextRetryTime))
                send(backoffEvent)
            },
            isRecoverable = { error ->
                // Only retry on recoverable errors (network issues, HTTP 5xx)
                // Never retry on signature failures or platform mismatches
                isRecoverableError(error)
            },
            operation = { fetchAndVerifyManifestOrThrow() }
        )

        val manifest = when {
            manifestResult.isSuccess -> manifestResult.getOrThrow()
            else -> {
                val error = manifestResult.exceptionOrNull()!!
                val updateError = createUpdateError(error)
                currentState.set(BundleUpdaterState.Error(updateError))
                send(BundleUpdateEvent.Error(updateError))
                return@channelFlow
            }
        }

        // Check for downgrade (buildNumber must be > current)
        if (manifest.buildNumber <= config.currentBuildNumber) {
            currentState.set(BundleUpdaterState.Idle)
            send(BundleUpdateEvent.UpToDate)
            return@channelFlow
        }

        // Determine download strategy
        val strategy = UpdateDecider.decide(manifest, config.platform, downloadManager.contentStore)

        if (strategy is DownloadStrategy.NoDownloadNeeded) {
            // Files already in CAS, just finalize
            finalizeUpdate(manifest)
            currentState.set(BundleUpdaterState.Ready(manifest.buildNumber))
            send(BundleUpdateEvent.UpdateReady(manifest.buildNumber))
            return@channelFlow
        }

        // Emit update available info
        val info = createUpdateInfo(manifest, strategy)
        send(BundleUpdateEvent.UpdateAvailable(info))

        // Download the bundle with retry
        val downloadResult = retryExecutor.executeWithRetry(
            emit = { backoffEvent ->
                currentState.set(BundleUpdaterState.BackingOff(backoffEvent.retryNumber, backoffEvent.nextRetryTime))
                send(backoffEvent)
            },
            isRecoverable = { error ->
                isRecoverableError(error)
            },
            operation = {
                downloadManager.downloadBundle(manifest) { progress ->
                    currentState.set(BundleUpdaterState.Downloading(progress))
                    trySend(BundleUpdateEvent.Downloading(progress))
                }.also { result ->
                    if (result is DownloadResult.Failure) {
                        throw DownloadFailureException(result.error, result.cause)
                    }
                }
            }
        )

        when {
            downloadResult.isSuccess -> {
                when (val result = downloadResult.getOrThrow()) {
                    is DownloadResult.Success -> {
                        finalizeUpdate(manifest)
                        currentState.set(BundleUpdaterState.Ready(manifest.buildNumber))
                        send(BundleUpdateEvent.UpdateReady(manifest.buildNumber))
                    }
                    is DownloadResult.AlreadyUpToDate -> {
                        currentState.set(BundleUpdaterState.Idle)
                        send(BundleUpdateEvent.UpToDate)
                    }
                    is DownloadResult.Cancelled -> {
                        currentState.set(BundleUpdaterState.Idle)
                    }
                    is DownloadResult.Failure -> {
                        // Should not reach here due to the throw above
                        val error = UpdateError(result.error, result.cause)
                        currentState.set(BundleUpdaterState.Error(error))
                        send(BundleUpdateEvent.Error(error))
                    }
                }
            }
            else -> {
                val error = downloadResult.exceptionOrNull()!!
                val updateError = createUpdateError(error)
                currentState.set(BundleUpdaterState.Error(updateError))
                send(BundleUpdateEvent.Error(updateError))
            }
        }
    }

    // ============ BACKGROUND SERVICE API (used by bundle for self-updates) ============

    /**
     * Start background update checking.
     *
     * Returns a Flow that emits update events. The updater runs continuously,
     * checking for updates at the configured interval and running cleanup cycles.
     * Network errors will trigger automatic retries with exponential backoff.
     *
     * After retries are exhausted, an [BundleUpdateEvent.Error] is emitted,
     * but the background loop continues to the next check interval.
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
                    val error = UpdateError(result.message, isRecoverable = false)
                    currentState.set(BundleUpdaterState.Error(error))
                    return UpdateCheckResult.Failed(result.message)
                }
                is ManifestFetchResult.PlatformMismatch -> {
                    val message = "Platform mismatch: expected ${result.expected}, got ${result.actual}"
                    val error = UpdateError(message, isRecoverable = false)
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
            val strategy = UpdateDecider.decide(manifest, config.platform, downloadManager.contentStore)

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

            val currentBuild = storageManager.getCurrentBuildNumber() ?: return null

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

    /** Exception wrapper for download failures to propagate through retry logic */
    private class DownloadFailureException(message: String, cause: Throwable?) : Exception(message, cause)

    /** Throws an exception for non-success results (used with retry executor) */
    private class ManifestFetchException(val result: ManifestFetchResult) : Exception(
        when (result) {
            is ManifestFetchResult.NetworkError -> result.message
            is ManifestFetchResult.SignatureFailure -> result.message
            is ManifestFetchResult.PlatformMismatch -> "Platform mismatch: expected ${result.expected}, got ${result.actual}"
            is ManifestFetchResult.Success -> "Unexpected success"
        },
        // Pass the underlying cause for NetworkError so isRecoverableError can inspect it
        when (result) {
            is ManifestFetchResult.NetworkError -> result.cause
            else -> null
        }
    )

    private suspend fun fetchAndVerifyManifest(): ManifestFetchResult {
        return try {
            val manifest = downloadManager.fetchManifest()

            // Verify signature
            if (!signatureVerifier.verifyManifest(manifest)) {
                return ManifestFetchResult.SignatureFailure("Manifest signature verification failed")
            }

            // Verify platform is supported
            if (!manifest.supportsPlatform(config.platform)) {
                val supported = manifest.platformBundles.keys.sorted().joinToString(", ")
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

    /** Throws appropriate exception for non-success manifest fetch results (for use with retry executor) */
    private suspend fun fetchAndVerifyManifestOrThrow(): BundleManifest {
        return when (val result = fetchAndVerifyManifest()) {
            is ManifestFetchResult.Success -> result.manifest
            is ManifestFetchResult.SignatureFailure -> throw ManifestFetchException(result)
            is ManifestFetchResult.PlatformMismatch -> throw ManifestFetchException(result)
            is ManifestFetchResult.NetworkError -> throw ManifestFetchException(result)
        }
    }

    private fun createUpdateError(error: Throwable): UpdateError {
        return when (error) {
            is ManifestFetchException -> when (val result = error.result) {
                is ManifestFetchResult.NetworkError -> UpdateError(result.message, result.cause, isRecoverable = true)
                is ManifestFetchResult.SignatureFailure -> UpdateError(result.message, isRecoverable = false)
                is ManifestFetchResult.PlatformMismatch -> UpdateError(
                    "Platform mismatch: expected ${result.expected}, got ${result.actual}",
                    isRecoverable = false
                )
                is ManifestFetchResult.Success -> UpdateError("Unexpected error", error)
            }
            is DownloadFailureException -> UpdateError(
                error.message ?: "Download failed",
                error.cause,
                isRecoverable = isRecoverableError(error.cause ?: error)
            )
            else -> UpdateError(
                error.message ?: "Unknown error",
                error,
                isRecoverable = isRecoverableError(error)
            )
        }
    }

    private fun createUpdateInfo(manifest: BundleManifest, strategy: DownloadStrategy): UpdateInfo {
        return when (strategy) {
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
            is DownloadStrategy.NoDownloadNeeded -> UpdateInfo(
                newBuildNumber = manifest.buildNumber,
                currentBuildNumber = config.currentBuildNumber,
                downloadSize = 0,
                isIncremental = false,
            )
        }
    }

    private suspend fun finalizeUpdate(manifest: BundleManifest) {
        storageManager.withStorageLock {
            // Use unlocked versions to avoid deadlock (both methods acquire the lock internally)
            storageManager.prepareVersionUnlocked(manifest, config.platform)
            storageManager.saveManifestUnlocked(json.encodeToString(manifest))
        }
    }

    private suspend fun ProducerScope<BundleUpdateEvent>.checkAndDownloadUpdate() {
        currentState.set(BundleUpdaterState.Checking)
        send(BundleUpdateEvent.Checking)

        // Fetch and verify manifest with retry
        val manifestResult = retryExecutor.executeWithRetry(
            emit = { backoffEvent ->
                currentState.set(BundleUpdaterState.BackingOff(backoffEvent.retryNumber, backoffEvent.nextRetryTime))
                send(backoffEvent)
            },
            isRecoverable = { error ->
                isRecoverableError(error)
            },
            operation = { fetchAndVerifyManifestOrThrow() }
        )

        val manifest = when {
            manifestResult.isSuccess -> manifestResult.getOrThrow()
            else -> {
                val error = manifestResult.exceptionOrNull()!!
                val updateError = createUpdateError(error)
                currentState.set(BundleUpdaterState.Error(updateError))
                send(BundleUpdateEvent.Error(updateError))
                // Background loop continues - just return from this check
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
        val strategy = UpdateDecider.decide(manifest, config.platform, downloadManager.contentStore)

        if (strategy is DownloadStrategy.NoDownloadNeeded) {
            // Files already in CAS, just finalize
            finalizeUpdate(manifest)
            currentState.set(BundleUpdaterState.Ready(manifest.buildNumber))
            send(BundleUpdateEvent.UpdateReady(manifest.buildNumber))
            return
        }

        // Notify update available
        val info = createUpdateInfo(manifest, strategy)
        send(BundleUpdateEvent.UpdateAvailable(info))

        // Download with retry
        val downloadResult = retryExecutor.executeWithRetry(
            emit = { backoffEvent ->
                currentState.set(BundleUpdaterState.BackingOff(backoffEvent.retryNumber, backoffEvent.nextRetryTime))
                send(backoffEvent)
            },
            isRecoverable = { error ->
                isRecoverableError(error)
            },
            operation = {
                downloadManager.downloadBundle(manifest) { progress ->
                    currentState.set(BundleUpdaterState.Downloading(progress))
                    trySend(BundleUpdateEvent.Downloading(progress))
                }.also { result ->
                    if (result is DownloadResult.Failure) {
                        throw DownloadFailureException(result.error, result.cause)
                    }
                }
            }
        )

        when {
            downloadResult.isSuccess -> {
                when (val result = downloadResult.getOrThrow()) {
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
            }
            else -> {
                val error = downloadResult.exceptionOrNull()!!
                val updateError = createUpdateError(error)
                currentState.set(BundleUpdaterState.Error(updateError))
                send(BundleUpdateEvent.Error(updateError))
                // Background loop continues - just return from this check
            }
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
