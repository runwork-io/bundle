package io.runwork.bundle.updater.storage

import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.updater.result.CleanupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages cleanup of old versions and orphaned CAS files.
 *
 * CRITICAL: Cleanup only runs when we're fully up-to-date.
 * This ensures files from interrupted downloads are preserved for recovery.
 */
class CleanupManager(
    private val storageManager: StorageManager,
    private val appDataDir: Path,
) {
    private val casDir = appDataDir.resolve("cas")
    private val versionsDir = appDataDir.resolve("versions")

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
        return storageManager.withStorageLock {
            doCleanup(currentManifest)
        }
    }

    private suspend fun doCleanup(currentManifest: BundleManifest): CleanupResult {
        val currentBuild = currentManifest.buildNumber

        // 1. Clear all temp files (always safe when up-to-date)
        storageManager.cleanupTemp()

        // 2. Delete old version directories (keep only current)
        val allVersions = storageManager.listVersions()
        val versionsToDelete = allVersions.filter { it != currentBuild }
        val deletedVersions = mutableListOf<Long>()

        for (version in versionsToDelete) {
            try {
                storageManager.deleteVersionDirectory(version)
                deletedVersions.add(version)
            } catch (e: Exception) {
                // Log but continue - don't fail cleanup for one bad version
            }
        }

        // 3. Find and delete orphaned CAS files (not needed by current version)
        val referencedHashes = currentManifest.files
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

    /**
     * Clean up CAS files that are no longer referenced by any version.
     *
     * This uses inode comparison for hard links to avoid expensive hash computation.
     * For files that aren't hard links (e.g., on Windows with copy fallback),
     * we still need to compute hashes.
     *
     * This is a more conservative cleanup that keeps files referenced by any version,
     * not just the current one. Use this when you want to preserve rollback capability.
     */
    suspend fun cleanupOrphanedCasFiles() {
        storageManager.withStorageLock {
            if (!withContext(Dispatchers.IO) { Files.exists(casDir) }) return@withStorageLock

            // Get all CAS file paths
            val casFilePaths = withContext(Dispatchers.IO) {
                Files.list(casDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.toList()
                }
            }

            if (casFilePaths.isEmpty()) return@withStorageLock

            // Compute file keys for CAS files
            val casFiles = casFilePaths.map { it to storageManager.getFileKey(it) }

            // Collect file keys (inodes) from version directories
            val referencedKeys = mutableSetOf<Any>()

            for (version in storageManager.listVersions()) {
                val versionDir = versionsDir.resolve(version.toString())
                if (!withContext(Dispatchers.IO) { Files.exists(versionDir) }) continue

                val versionFiles = withContext(Dispatchers.IO) {
                    Files.walk(versionDir).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) && it.fileName.toString() != ".complete" }
                            .toList()
                    }
                }

                for (file in versionFiles) {
                    referencedKeys.add(storageManager.getFileKey(file))
                }
            }

            // Delete CAS files whose inode is not referenced
            for ((casFile, fileKey) in casFiles) {
                if (fileKey !in referencedKeys) {
                    withContext(Dispatchers.IO) {
                        try {
                            Files.deleteIfExists(casFile)
                        } catch (e: Exception) {
                            // Ignore deletion failures
                        }
                    }
                }
            }
        }
    }
}
