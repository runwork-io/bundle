package io.runwork.bundle.updater.storage

import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.storage.ContentAddressableStore
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.common.verification.VerificationFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Manages bundle versions and file organization.
 *
 * Responsibilities:
 * - Version directory management (create, list, delete)
 * - Hard-linking files from CAS into version directories
 * - Current version tracking
 * - Thread-safe storage operations via mutex
 *
 * All write operations are protected by a mutex to prevent concurrent modifications.
 */
class StorageManager(
    private val appDataDir: Path
) {
    val contentStore = ContentAddressableStore(appDataDir.resolve("cas"))
    private val versionsDir = appDataDir.resolve("versions")
    private val tempDir = appDataDir.resolve("temp")
    private val currentPath = appDataDir.resolve("current")
    private val manifestPath = appDataDir.resolve("manifest.json")

    /** Mutex protecting all write operations to storage */
    private val storageMutex = Mutex()

    /**
     * Execute a block with the storage lock held.
     * Use this for operations that need to atomically read and write storage.
     */
    suspend fun <T> withStorageLock(block: suspend () -> T): T {
        return storageMutex.withLock { block() }
    }

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
        if (!Files.exists(versionsDir)) return@withContext listOf()

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
     * List all complete version build numbers (those with .complete marker).
     */
    suspend fun listCompleteVersions(): List<Long> = withContext(Dispatchers.IO) {
        if (!Files.exists(versionsDir)) return@withContext listOf()

        Files.list(versionsDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .filter { Files.exists(it.resolve(".complete")) }
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
     * This operation is idempotent - if the version directory already exists with some files,
     * those files are skipped, allowing recovery from partial failures.
     *
     * @param manifest The bundle manifest describing all files
     * @throws IllegalStateException if a required file is missing from CAS
     */
    suspend fun prepareVersion(manifest: BundleManifest) {
        storageMutex.withLock {
            ensureDirectoriesExist()
            val versionDir = versionsDir.resolve(manifest.buildNumber.toString())

            // If already complete, skip
            if (withContext(Dispatchers.IO) { Files.exists(versionDir.resolve(".complete")) }) {
                return
            }

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
    suspend fun setCurrentVersion(buildNumber: Long) {
        storageMutex.withLock {
            withContext(Dispatchers.IO) {
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
        }
    }

    /**
     * Save the current manifest to disk.
     */
    suspend fun saveManifest(manifestJson: String) {
        storageMutex.withLock {
            withContext(Dispatchers.IO) {
                Files.createDirectories(appDataDir)
                Files.writeString(manifestPath, manifestJson)
            }
        }
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
     * Delete a version directory and all its contents.
     */
    suspend fun deleteVersionDirectory(buildNumber: Long) {
        storageMutex.withLock {
            withContext(Dispatchers.IO) {
                val versionDir = versionsDir.resolve(buildNumber.toString())
                if (!Files.exists(versionDir)) return@withContext

                Files.walk(versionDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.delete(it) }
            }
        }
    }

    /**
     * Get the size of a CAS file.
     */
    suspend fun getCasFileSize(hash: String): Long = withContext(Dispatchers.IO) {
        val path = contentStore.getPath(hash) ?: return@withContext 0L
        try {
            Files.size(path)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get a unique file key (typically inode on Unix, file key on Windows).
     * This allows efficient comparison of hard links without computing hashes.
     */
    suspend fun getFileKey(path: Path): Any {
        val fileKey = withContext(Dispatchers.IO) {
            try {
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
    suspend fun cleanupTemp() {
        storageMutex.withLock {
            withContext(Dispatchers.IO) {
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

        val filesToVerify = manifest.files.map { file ->
            versionDir.resolve(file.path) to file.hash
        }

        val results = HashVerifier.verifyFilesConcurrently(filesToVerify, parallelism = 5)

        return results.filterNot { it.success }.map { result ->
            VerificationFailure(
                path = versionDir.relativize(result.path).toString(),
                expectedHash = result.expectedHash,
                actualHash = result.actualHash,
                reason = if (result.actualHash == null) "File missing" else "Hash mismatch"
            )
        }
    }
}
