package io.runwork.bundle.updater.storage

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.updater.result.CleanupResult
import java.nio.file.Path

/**
 * Manages cleanup of old versions and orphaned CAS files.
 *
 * CRITICAL: Cleanup only runs when we're fully up-to-date.
 * This ensures files from interrupted downloads are preserved for recovery.
 */
class CleanupManager(
    private val storageManager: StorageManager,
    @Suppress("unused") private val bundleDir: Path,
    private val platform: Platform,
) {
    /**
     * Perform cleanup of old versions and orphaned CAS files.
     *
     * This should ONLY be called when:
     * 1. We have checked for updates from the server
     * 2. No update is available (current version == latest)
     * 3. Current version is valid and complete
     *
     * @param currentManifest The current manifest (latest version we're running)
     * @return Cleanup result with statistics
     */
    suspend fun cleanup(currentManifest: BundleManifest): CleanupResult {
        return storageManager.withWriteScope { scope ->
            doCleanup(scope, currentManifest)
        }
    }

    private suspend fun doCleanup(
        scope: StorageManager.StorageManagerWriteScope,
        currentManifest: BundleManifest,
    ): CleanupResult {
        val currentBuild = currentManifest.buildNumber

        // 1. Clear all temp files (always safe when up-to-date)
        scope.cleanupTemp()

        // 2. Delete old version directories (keep only current)
        val allVersions = storageManager.listVersions()
        val versionsToDelete = allVersions.filter { it != currentBuild }
        val deletedVersions = mutableListOf<Long>()

        for (version in versionsToDelete) {
            try {
                scope.deleteVersionDirectory(version)
                deletedVersions.add(version)
            } catch (e: Exception) {
                // Log but continue - don't fail cleanup for one bad version
            }
        }

        // 3. Find and delete orphaned CAS files (not needed by current version for this platform)
        val referencedHashes = currentManifest.filesForPlatform(platform)
            .map { it.hash }
            .toSet()

        val allCasHashes = storageManager.contentStore.listHashes()
        val orphanedHashes = allCasHashes.filter { it !in referencedHashes }

        var bytesFreed = 0L
        var filesRemoved = 0

        for (hash in orphanedHashes) {
            try {
                bytesFreed += storageManager.getCasFileSize(hash)
                if (storageManager.contentStore.delete(hash)) {
                    filesRemoved++
                }
            } catch (e: Exception) {
                // Log but continue
            }
        }

        return CleanupResult(
            versionsRemoved = deletedVersions,
            casFilesRemoved = filesRemoved,
            bytesFreed = bytesFreed,
        )
    }
}
