package io.runwork.bundle

import io.runwork.bundle.download.DownloadManager
import io.runwork.bundle.download.DownloadProgress
import io.runwork.bundle.download.DownloadResult
import io.runwork.bundle.loader.BundleLoader
import io.runwork.bundle.loader.LoadedBundle
import io.runwork.bundle.manifest.BundleManifest
import io.runwork.bundle.storage.StorageManager
import io.runwork.bundle.storage.VerificationFailure
import io.runwork.bundle.verification.SignatureVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Main entry point for bundle management operations.
 *
 * Used by the shell to:
 * - Check for updates
 * - Download bundles (full or incremental)
 * - Verify bundle integrity
 * - Repair corrupted files
 * - Load bundles into isolated classloaders
 *
 * The shell should:
 * 1. Create a BundleManager with configuration
 * 2. On startup: checkForUpdate() to get UpdateCheckResult
 * 3. Handle UpdateCheckResult appropriately:
 *    - UpToDate: proceed to verify and load
 *    - UpdateAvailable: download the update
 *    - CorruptedBundle: show updater UI and re-download everything
 *    - NetworkError: proceed with cached bundle if available
 * 4. verifyBundle() before loading - if it fails, show updater UI
 * 5. loadBundle() to start the application
 */
class BundleManager(
    private val config: BundleConfig,
) : Closeable {
    private val storageManager = StorageManager(config.appDataDir)
    private val downloadManager = DownloadManager(config.baseUrl, storageManager)
    private val signatureVerifier = SignatureVerifier(config.publicKey)
    private val bundleLoader = BundleLoader(storageManager)

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    /**
     * Check for updates from the server with proper error handling.
     *
     * This method uses exponential backoff for retries and properly distinguishes
     * between different failure modes.
     *
     * @return UpdateCheckResult indicating the outcome
     */
    suspend fun checkForUpdate(): UpdateCheckResult {
        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRIES) {
            val manifest = try {
                downloadManager.fetchManifest()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(retryDelay)
                    retryDelay *= 2 // Exponential backoff
                    continue
                }
                // After all retries failed, return network error
                return UpdateCheckResult.NetworkError(e.message ?: "Network error")
            }

            // Verify signature - if invalid, retry (could be CDN corruption)
            if (!signatureVerifier.verifyManifest(manifest)) {
                if (attempt < MAX_RETRIES) {
                    delay(retryDelay)
                    retryDelay *= 2
                    continue
                }
                // After all retries, signature is still invalid - this is a security issue
                return UpdateCheckResult.SignatureInvalid(
                    "Manifest signature verification failed after $MAX_RETRIES attempts"
                )
            }

            // Check platform
            if (manifest.platform != config.platform) {
                return UpdateCheckResult.PlatformMismatch(
                    expected = config.platform,
                    actual = manifest.platform
                )
            }

            val currentVersion = getCurrentBuildNumber() ?: 0
            if (manifest.buildNumber <= currentVersion) {
                // Already up to date
                return UpdateCheckResult.UpToDate(currentVersion)
            }

            return UpdateCheckResult.UpdateAvailable(
                UpdateInfo(
                    buildNumber = manifest.buildNumber,
                    currentBuildNumber = currentVersion,
                    manifest = manifest,
                )
            )
        }

        // Should not reach here, but handle it
        return UpdateCheckResult.NetworkError(lastException?.message ?: "Unknown error")
    }

    /**
     * Verify the local bundle integrity.
     *
     * If verification fails, the shell should show the updater UI and trigger
     * a full re-download via forceRedownload().
     *
     * @return BundleVerificationResult indicating the outcome
     */
    suspend fun verifyLocalBundle(): BundleVerificationResult {
        val manifestJson = storageManager.loadManifest()
            ?: return BundleVerificationResult.NoBundleInstalled

        val manifest = try {
            json.decodeFromString<BundleManifest>(manifestJson)
        } catch (e: Exception) {
            return BundleVerificationResult.Corrupted(
                reason = "Invalid manifest JSON",
                failures = listOf(VerificationFailure("manifest.json", "", null, "Invalid JSON"))
            )
        }

        // Verify manifest signature
        if (!signatureVerifier.verifyManifest(manifest)) {
            return BundleVerificationResult.Corrupted(
                reason = "Manifest signature invalid - possible tampering",
                failures = listOf(VerificationFailure("manifest.json", "", null, "Invalid signature"))
            )
        }

        // Verify all file hashes
        val failures = storageManager.verifyVersion(manifest)
        if (failures.isNotEmpty()) {
            return BundleVerificationResult.Corrupted(
                reason = "File integrity check failed",
                failures = failures
            )
        }

        return BundleVerificationResult.Valid(manifest)
    }

    /**
     * Force a complete re-download of the bundle.
     *
     * This should be called when verification fails. It clears the local
     * bundle and downloads everything fresh from the server.
     *
     * @param progressCallback Called with download progress
     * @return Download result
     */
    suspend fun forceRedownload(
        progressCallback: suspend (DownloadProgress) -> Unit
    ): DownloadResult {
        // Note: We don't clean up old versions here - that happens after successful download.
        // Cleaning up before download could delete the current working version before
        // the new one is ready.

        // Fetch fresh manifest with retries
        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY_MS

        for (attempt in 1..MAX_RETRIES) {
            val manifest = try {
                downloadManager.fetchManifest()
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(retryDelay)
                    retryDelay *= 2
                    continue
                }
                return DownloadResult.Failure("Failed to fetch manifest: ${e.message}", e)
            }

            // Verify signature
            if (!signatureVerifier.verifyManifest(manifest)) {
                if (attempt < MAX_RETRIES) {
                    delay(retryDelay)
                    retryDelay *= 2
                    continue
                }
                return DownloadResult.Failure("Manifest signature invalid after $MAX_RETRIES attempts")
            }

            // Check platform
            if (manifest.platform != config.platform) {
                return DownloadResult.Failure(
                    "Platform mismatch: expected ${config.platform}, got ${manifest.platform}"
                )
            }

            // Download the bundle
            val result = downloadManager.downloadBundle(manifest, progressCallback)

            if (result is DownloadResult.Success) {
                // Prepare the version directory
                storageManager.prepareVersion(manifest)

                // Save the manifest
                storageManager.saveManifest(json.encodeToString(manifest))

                // Set as current version
                storageManager.setCurrentVersion(manifest.buildNumber)

                // Note: Don't clean up old versions here - the old version may still be running.
                // Cleanup happens at next app startup via cleanup().
            }

            return result
        }

        return DownloadResult.Failure("Failed after $MAX_RETRIES attempts: ${lastException?.message}")
    }

    /**
     * Download an update.
     *
     * @param updateInfo Update information from checkForUpdate()
     * @param progressCallback Called with download progress
     * @return Download result
     */
    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        progressCallback: suspend (DownloadProgress) -> Unit
    ): DownloadResult {
        val result = downloadManager.downloadBundle(updateInfo.manifest, progressCallback)

        if (result is DownloadResult.Success) {
            // Prepare the version directory (hard-link files from CAS)
            storageManager.prepareVersion(updateInfo.manifest)

            // Save the manifest
            storageManager.saveManifest(json.encodeToString(updateInfo.manifest))

            // Set as current version
            storageManager.setCurrentVersion(updateInfo.manifest.buildNumber)

            // Note: Don't clean up old versions here - the old version may still be running.
            // Cleanup happens at next app startup via cleanup().
        }

        return result
    }

    /**
     * Repair files that failed verification by re-downloading them.
     *
     * @param failures List of verification failures to repair
     * @param progressCallback Called with download progress
     * @return Repair result
     */
    suspend fun repairBundle(
        failures: List<VerificationFailure>,
        progressCallback: suspend (DownloadProgress) -> Unit
    ): RepairResult {
        if (failures.isEmpty()) {
            return RepairResult.Success
        }

        val manifestJson = storageManager.loadManifest()
            ?: return RepairResult.Failure("No manifest found")

        val manifest = try {
            json.decodeFromString<BundleManifest>(manifestJson)
        } catch (e: Exception) {
            return RepairResult.Failure("Invalid manifest")
        }

        // Find the files to re-download
        val filesToRepair = failures.mapNotNull { failure ->
            manifest.files.find { it.path == failure.path }
        }

        if (filesToRepair.isEmpty()) {
            return RepairResult.Failure("No repairable files found")
        }

        // Delete corrupted files from CAS
        for (failure in failures) {
            if (failure.actualHash != null) {
                storageManager.contentStore.delete(failure.actualHash)
            }
        }

        // Delete corrupted hard links in version directory
        val versionDir = storageManager.getVersionPath(manifest.buildNumber)
        withContext(Dispatchers.IO) {
            for (failure in failures) {
                val filePath = versionDir.resolve(failure.path)
                try {
                    Files.deleteIfExists(filePath)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        // Re-download the files
        // Create a temporary manifest with only the files to repair
        val repairManifest = manifest.copy(
            files = filesToRepair,
            totalSize = filesToRepair.sumOf { it.size }
        )

        val result = downloadManager.downloadBundle(repairManifest, progressCallback)

        return when (result) {
            is DownloadResult.Success -> {
                // Re-link the repaired files
                for (file in filesToRepair) {
                    val sourcePath = storageManager.contentStore.getPath(file.hash)
                        ?: return RepairResult.Failure("Repaired file not in CAS: ${file.path}")

                    val destPath = versionDir.resolve(file.path)
                    withContext(Dispatchers.IO) {
                        Files.createDirectories(destPath.parent)

                        try {
                            Files.createLink(destPath, sourcePath)
                        } catch (e: Exception) {
                            Files.copy(
                                sourcePath,
                                destPath,
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }
                    }
                }
                RepairResult.Success
            }

            is DownloadResult.Failure -> {
                RepairResult.Failure(result.error)
            }

            is DownloadResult.Cancelled -> {
                RepairResult.Failure("Repair cancelled")
            }
        }
    }

    /**
     * Load the current bundle into an isolated classloader.
     *
     * @return The loaded bundle with communication bridge
     * @throws BundleLoadException if loading fails
     */
    suspend fun loadBundle(): LoadedBundle {
        val manifestJson = storageManager.loadManifest()
            ?: throw io.runwork.bundle.loader.BundleLoadException("No manifest found")

        val manifest = try {
            json.decodeFromString<BundleManifest>(manifestJson)
        } catch (e: Exception) {
            throw io.runwork.bundle.loader.BundleLoadException("Invalid manifest", e)
        }

        return bundleLoader.load(manifest)
    }

    /**
     * Get the current bundle build number.
     *
     * @return The current build number, or null if no bundle is installed
     */
    suspend fun getCurrentBuildNumber(): Long? {
        return storageManager.getCurrentVersion()
    }

    /**
     * Get the current manifest.
     *
     * @return The current manifest, or null if none is installed
     */
    suspend fun getCurrentManifest(): BundleManifest? {
        val manifestJson = storageManager.loadManifest() ?: return null
        return try {
            json.decodeFromString<BundleManifest>(manifestJson)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a bundle is installed and ready.
     */
    suspend fun hasBundleInstalled(): Boolean {
        val version = getCurrentBuildNumber() ?: return false
        return storageManager.hasVersion(version)
    }

    /**
     * Clean up temporary files and old versions.
     */
    suspend fun cleanup() {
        storageManager.cleanupTemp()
        storageManager.cleanupOldVersions()
    }

    /**
     * Close the bundle manager and release resources.
     */
    override fun close() {
        downloadManager.close()
    }
}

/**
 * Information about an available update.
 */
data class UpdateInfo(
    /** The new build number */
    val buildNumber: Long,

    /** The currently installed build number */
    val currentBuildNumber: Long,

    /** The full manifest for the update */
    val manifest: BundleManifest,
)

/**
 * Result of a repair operation.
 */
sealed class RepairResult {
    data object Success : RepairResult()
    data class Failure(val error: String) : RepairResult()
}

/**
 * Result of checking for updates.
 *
 * The shell should handle each case appropriately:
 * - UpdateAvailable: Optionally download the update
 * - UpToDate: Proceed to load the bundle
 * - NetworkError: Proceed with cached bundle if available
 * - SignatureInvalid: Security issue - show updater UI and re-download
 * - PlatformMismatch: Configuration error
 */
sealed class UpdateCheckResult {
    /** An update is available for download */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()

    /** The current bundle is up to date */
    data class UpToDate(val buildNumber: Long) : UpdateCheckResult()

    /** Network error occurred - can proceed with cached bundle if available */
    data class NetworkError(val message: String) : UpdateCheckResult()

    /**
     * Manifest signature verification failed after retries.
     * This is a security issue - the shell should show updater UI and force re-download.
     */
    data class SignatureInvalid(val message: String) : UpdateCheckResult()

    /** Platform mismatch between manifest and config */
    data class PlatformMismatch(val expected: String, val actual: String) : UpdateCheckResult()
}

/**
 * Result of verifying the local bundle.
 *
 * If the result is Corrupted, the shell should show the updater UI and
 * call forceRedownload() to get a fresh copy.
 */
sealed class BundleVerificationResult {
    /** Bundle is valid and can be loaded */
    data class Valid(val manifest: BundleManifest) : BundleVerificationResult()

    /** Bundle is corrupted or tampered with - must re-download */
    data class Corrupted(
        val reason: String,
        val failures: List<VerificationFailure>
    ) : BundleVerificationResult()

    /** No bundle is installed yet */
    data object NoBundleInstalled : BundleVerificationResult()
}
