package io.runwork.bundle.storage

import io.runwork.bundle.manifest.BundleManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    init {
        Files.createDirectories(versionsDir)
        Files.createDirectories(tempDir)
    }

    /**
     * Check if a version is fully downloaded and prepared.
     */
    fun hasVersion(buildNumber: Long): Boolean {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        return Files.exists(versionDir.resolve(".complete"))
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
    fun listVersions(): List<Long> {
        if (!Files.exists(versionsDir)) return emptyList()

        return Files.list(versionsDir).use { stream ->
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
     * @param manifest The bundle manifest describing all files
     * @throws IllegalStateException if a required file is missing from CAS
     */
    fun prepareVersion(manifest: BundleManifest) {
        val versionDir = versionsDir.resolve(manifest.buildNumber.toString())
        Files.createDirectories(versionDir)

        for (file in manifest.files) {
            val sourcePath = contentStore.getPath(file.hash)
                ?: throw IllegalStateException("File not in CAS: ${file.hash} (${file.path})")

            val destPath = versionDir.resolve(file.path)
            Files.createDirectories(destPath.parent)

            if (Files.exists(destPath)) continue

            try {
                // Try hard link first (most efficient, no data duplication)
                Files.createLink(destPath, sourcePath)
            } catch (e: Exception) {
                // Fall back to copy if hard link fails
                // This can happen on Windows in some cases, or cross-filesystem
                Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        // Mark as complete
        Files.createFile(versionDir.resolve(".complete"))
    }

    /**
     * Get the current active version build number.
     *
     * On Mac/Linux, this reads a symlink pointing to the version directory.
     * On Windows, this reads a text file containing the version number.
     *
     * @return The current version build number, or null if none is set
     */
    fun getCurrentVersion(): Long? {
        if (!Files.exists(currentPath)) return null

        // Try symlink first (Mac/Linux), then fall back to text file (Windows)
        // Using try-catch instead of checking type first to avoid race conditions
        return try {
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
    fun setCurrentVersion(buildNumber: Long) {
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
    fun saveManifest(manifestJson: String) {
        Files.writeString(manifestPath, manifestJson)
    }

    /**
     * Load the saved manifest from disk.
     *
     * @return The manifest JSON string, or null if not found
     */
    fun loadManifest(): String? {
        if (!Files.exists(manifestPath)) return null
        return Files.readString(manifestPath)
    }

    /**
     * Clean up old versions, keeping current and one previous.
     *
     * Also cleans up orphaned CAS files that are no longer referenced
     * by any version.
     */
    fun cleanupOldVersions() {
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
    private fun deleteVersionDirectory(buildNumber: Long) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        if (!Files.exists(versionDir)) return

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
     */
    private fun cleanupOrphanedCasFiles() {
        // Build a set of CAS file inodes that are referenced by version directories
        val casDir = appDataDir.resolve("cas")
        if (!Files.exists(casDir)) return

        // Get all CAS file paths and their inodes
        val casFiles = Files.list(casDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it to getFileKey(it) }
                .toList()
        }

        if (casFiles.isEmpty()) return

        // Collect file keys (inodes) from version directories
        val referencedKeys = mutableSetOf<Any>()

        for (version in listVersions()) {
            val versionDir = versionsDir.resolve(version.toString())
            if (!Files.exists(versionDir)) continue

            Files.walk(versionDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString() != ".complete" }
                    .forEach { file ->
                        referencedKeys.add(getFileKey(file))
                    }
            }
        }

        // Delete CAS files whose inode is not referenced
        for ((casFile, fileKey) in casFiles) {
            if (fileKey !in referencedKeys) {
                try {
                    Files.deleteIfExists(casFile)
                } catch (e: Exception) {
                    // Ignore deletion failures
                }
            }
        }
    }

    /**
     * Get a unique file key (typically inode on Unix, file key on Windows).
     * This allows efficient comparison of hard links without computing hashes.
     */
    private fun getFileKey(path: Path): Any {
        return try {
            // On Windows, fileKey() returns null, so we need to fall back to hash
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                ?: contentStore.computeHash(path)
        } catch (e: Exception) {
            // Fallback: use the hash if we can't get the file key
            contentStore.computeHash(path)
        }
    }

    /**
     * Create a temporary file for downloading.
     *
     * @param prefix Prefix for the temp file name
     * @return Path to the new temporary file
     */
    fun createTempFile(prefix: String): Path {
        return Files.createTempFile(tempDir, prefix, ".tmp")
    }

    /**
     * Clean up the temp directory.
     */
    fun cleanupTemp() {
        if (!Files.exists(tempDir)) return

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
    fun verifyVersion(manifest: BundleManifest): List<VerificationFailure> {
        val versionDir = versionsDir.resolve(manifest.buildNumber.toString())
        val failures = mutableListOf<VerificationFailure>()

        for (file in manifest.files) {
            val filePath = versionDir.resolve(file.path)

            if (!Files.exists(filePath)) {
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
