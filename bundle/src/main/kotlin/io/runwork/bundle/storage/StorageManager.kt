package io.runwork.bundle.storage

import io.runwork.bundle.manifest.BundleManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

/**
 * Manages bundle versions and file organization.
 *
 * Responsibilities:
 * - Version directory management (create, list, delete)
 * - Hard-linking files from CAS into version directories
 * - Current version tracking
 * - Cleanup of old versions and orphaned CAS files
 */
class StorageManager(
    private val appDataDir: Path
) {
    val contentStore = ContentAddressableStore(appDataDir.resolve("cas"))
    private val versionsDir = appDataDir.resolve("versions")
    private val tempDir = appDataDir.resolve("temp")
    private val currentPath = appDataDir.resolve("current")
    private val manifestPath = appDataDir.resolve("manifest.json")

    /**
     * Ensure required directories exist.
     */
    private suspend fun ensureDirectoriesExist() = withContext(Dispatchers.IO) {
        Files.createDirectories(versionsDir)
        Files.createDirectories(tempDir)
    }

    /**
     * Check if a version is fully downloaded and prepared.
     */
    suspend fun hasVersion(buildNumber: Long): Boolean = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        Files.exists(versionDir.resolve(".complete"))
    }

    /**
     * Get the path to a version's directory.
     */
    fun getVersionPath(buildNumber: Long): Path {
        return versionsDir.resolve(buildNumber.toString())
    }

    /**
     * List all available version build numbers, sorted ascending.
     */
    suspend fun listVersions(): List<Long> = withContext(Dispatchers.IO) {
        if (!Files.exists(versionsDir)) return@withContext emptyList()

        Files.list(versionsDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString().toLongOrNull() }
                .filter { it != null }
                .map { it!! }
                .sorted()
                .toList()
        }
    }

    /**
     * Prepare a version directory by hard-linking files from CAS.
     *
     * For each file in the manifest:
     * 1. Look up the file in CAS by hash
     * 2. Create parent directories in the version directory
     * 3. Create a hard link from version/{path} to CAS/{hash}
     * 4. Fall back to copy if hard link fails (e.g., cross-filesystem)
     *
     * Note: If this function fails partway through (e.g., after creating some hard links),
     * the partial version directory remains on disk. On retry, existing files are skipped,
     * allowing recovery from transient failures. The `.complete` marker file is only created
     * after all files are successfully linked, so partial versions won't be considered valid.
     *
     * @param manifest The bundle manifest describing all files
     * @throws IllegalStateException if a required file is missing from CAS
     */
    suspend fun prepareVersion(manifest: BundleManifest) {
        ensureDirectoriesExist()
        val versionDir = versionsDir.resolve(manifest.buildNumber.toString())

        withContext(Dispatchers.IO) {
            Files.createDirectories(versionDir)
        }

        for (file in manifest.files) {
            val sourcePath = contentStore.getPath(file.hash)
                ?: throw IllegalStateException("File not in CAS: ${file.hash} (${file.path})")

            val destPath = versionDir.resolve(file.path)

            // Skip if file already exists (allows recovery from partial failures)
            if (withContext(Dispatchers.IO) { Files.exists(destPath) }) continue

            withContext(Dispatchers.IO) {
                Files.createDirectories(destPath.parent)

                try {
                    // Try hard link first (most efficient, no data duplication)
                    Files.createLink(destPath, sourcePath)
                } catch (e: Exception) {
                    // Fall back to copy if hard link fails
                    // This can happen on Windows in some cases, or cross-filesystem
                    Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        // Mark as complete
        withContext(Dispatchers.IO) {
            Files.createFile(versionDir.resolve(".complete"))
        }
    }

    /**
     * Get the current active version build number.
     *
     * On Mac/Linux, this reads a symlink pointing to the version directory.
     * On Windows, this reads a text file containing the version number.
     *
     * @return The current version build number, or null if none is set
     */
    suspend fun getCurrentVersion(): Long? = withContext(Dispatchers.IO) {
        if (!Files.exists(currentPath)) return@withContext null

        // Try symlink first (Mac/Linux), then fall back to text file (Windows)
        // Using try-catch instead of checking type first to avoid race conditions
        try {
            // Try to read as symlink
            val target = Files.readSymbolicLink(currentPath)
            target.fileName.toString().toLongOrNull()
        } catch (e: java.nio.file.NotLinkException) {
            // Not a symlink, try as regular file
            try {
                Files.readString(currentPath).trim().toLongOrNull()
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Set the current active version.
     *
     * On Mac/Linux, this creates a symlink to the version directory.
     * On Windows, this writes a text file containing the version number.
     *
     * @param buildNumber The version to set as current
     * @throws IllegalStateException if unable to set the current version
     */
    suspend fun setCurrentVersion(buildNumber: Long) = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())

        Files.deleteIfExists(currentPath)

        try {
            // Try symlink first (Mac/Linux)
            Files.createSymbolicLink(currentPath, versionDir)
        } catch (symlinkException: Exception) {
            // Windows fallback: write version to text file
            try {
                Files.writeString(currentPath, buildNumber.toString())
            } catch (writeException: Exception) {
                writeException.addSuppressed(symlinkException)
                throw IllegalStateException(
                    "Failed to set current version to $buildNumber: " +
                        "symlink failed and text file fallback also failed",
                    writeException
                )
            }
        }
    }

    /**
     * Save the current manifest to disk.
     */
    suspend fun saveManifest(manifestJson: String) = withContext(Dispatchers.IO) {
        Files.writeString(manifestPath, manifestJson)
    }

    /**
     * Load the saved manifest from disk.
     *
     * @return The manifest JSON string, or null if not found
     */
    suspend fun loadManifest(): String? = withContext(Dispatchers.IO) {
        if (!Files.exists(manifestPath)) return@withContext null
        Files.readString(manifestPath)
    }

    /**
     * Clean up old versions, keeping current and one previous.
     *
     * Also cleans up orphaned CAS files that are no longer referenced
     * by any version.
     */
    suspend fun cleanupOldVersions() {
        val current = getCurrentVersion() ?: return
        val versions = listVersions()

        // Find the previous version (highest version < current)
        val previous = versions.filter { it < current }.maxOrNull()

        // Keep current and one previous
        val toKeep = setOfNotNull(current, previous)

        // Delete old versions
        for (version in versions) {
            if (version !in toKeep) {
                deleteVersionDirectory(version)
            }
        }

        // Clean up orphaned CAS files
        cleanupOrphanedCasFiles()
    }

    /**
     * Delete a version directory and all its contents.
     */
    private suspend fun deleteVersionDirectory(buildNumber: Long) = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        if (!Files.exists(versionDir)) return@withContext

        Files.walk(versionDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.delete(it) }
    }

    /**
     * Clean up CAS files that are no longer referenced by any version.
     *
     * This uses inode comparison for hard links to avoid expensive hash computation.
     * For files that aren't hard links (e.g., on Windows with copy fallback),
     * we still need to compute hashes.
     *
     * Files are processed sequentially (one at a time) to minimize system load,
     * as this cleanup runs in the background and shouldn't impact foreground operations.
     */
    private suspend fun cleanupOrphanedCasFiles() {
        // Build a set of CAS file inodes that are referenced by version directories
        val casDir = appDataDir.resolve("cas")
        if (!withContext(Dispatchers.IO) { Files.exists(casDir) }) return

        // Get all CAS file paths
        val casFilePaths = withContext(Dispatchers.IO) {
            Files.list(casDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        }

        if (casFilePaths.isEmpty()) return

        // Compute file keys for CAS files
        val casFiles = casFilePaths.map { it to getFileKey(it) }

        // Collect file keys (inodes) from version directories
        val referencedKeys = mutableSetOf<Any>()

        for (version in listVersions()) {
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
                referencedKeys.add(getFileKey(file))
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

    /**
     * Get a unique file key (typically inode on Unix, file key on Windows).
     * This allows efficient comparison of hard links without computing hashes.
     */
    private suspend fun getFileKey(path: Path): Any {
        val fileKey = withContext(Dispatchers.IO) {
            try {
                // On Windows, fileKey() returns null, so we need to fall back to hash
                Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
            } catch (e: Exception) {
                null
            }
        }
        return fileKey ?: contentStore.computeHash(path)
    }

    /**
     * Create a temporary file for downloading.
     *
     * @param prefix Prefix for the temp file name
     * @return Path to the new temporary file
     */
    suspend fun createTempFile(prefix: String): Path {
        ensureDirectoriesExist()
        return withContext(Dispatchers.IO) {
            Files.createTempFile(tempDir, prefix, ".tmp")
        }
    }

    /**
     * Clean up the temp directory.
     */
    suspend fun cleanupTemp() = withContext(Dispatchers.IO) {
        if (!Files.exists(tempDir)) return@withContext

        Files.list(tempDir).use { stream ->
            stream.forEach { file ->
                try {
                    Files.deleteIfExists(file)
                } catch (e: Exception) {
                    // Ignore - file might be in use
                }
            }
        }
    }

    /**
     * Verify all files in a version match their expected hashes.
     *
     * @param manifest The manifest to verify against
     * @return List of files that failed verification (empty if all passed)
     */
    suspend fun verifyVersion(manifest: BundleManifest): List<VerificationFailure> {
        val versionDir = versionsDir.resolve(manifest.buildNumber.toString())
        val failures = mutableListOf<VerificationFailure>()

        for (file in manifest.files) {
            val filePath = versionDir.resolve(file.path)

            if (!withContext(Dispatchers.IO) { Files.exists(filePath) }) {
                failures.add(VerificationFailure(file.path, file.hash, null, "File missing"))
                continue
            }

            val actualHash = contentStore.computeHash(filePath)
            if (actualHash != file.hash) {
                failures.add(VerificationFailure(file.path, file.hash, actualHash, "Hash mismatch"))
            }
        }

        return failures
    }
}

/**
 * Represents a file that failed verification.
 */
data class VerificationFailure(
    /** Relative path of the file */
    val path: String,
    /** Expected SHA-256 hash */
    val expectedHash: String,
    /** Actual SHA-256 hash (null if file missing) */
    val actualHash: String?,
    /** Reason for failure */
    val reason: String,
)
