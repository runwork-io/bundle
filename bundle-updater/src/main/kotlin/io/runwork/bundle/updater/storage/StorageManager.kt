package io.runwork.bundle.updater.storage

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.createLink
import io.runwork.bundle.common.isSameFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.storage.ContentAddressableStore
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.common.verification.VerificationFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
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
 * Write operations are only accessible within a [StorageManagerWriteScope], obtained via [withWriteScope].
 * This makes it impossible to accidentally call write methods without holding the lock.
 */
class StorageManager(
    private val bundleDir: Path
) {
    val contentStore = ContentAddressableStore(bundleDir.resolve("cas"))
    private val versionsDir = bundleDir.resolve("versions")
    private val tempDir = bundleDir.resolve("temp")
    private val manifestPath = bundleDir.resolve("manifest.json")

    /** Mutex protecting all write operations to storage */
    private val storageMutex = Mutex()

    private val writeScope = StorageManagerWriteScope()

    /**
     * Execute a block with the storage lock held, providing a [StorageManagerWriteScope]
     * that exposes write operations.
     *
     * All write operations (prepareVersion, saveManifest, deleteVersionDirectory, cleanupTemp)
     * are only accessible through the scope parameter, ensuring the lock is always held.
     */
    suspend fun <T> withWriteScope(block: suspend (StorageManagerWriteScope) -> T): T {
        return storageMutex.withLock {
            block(writeScope)
        }
    }

    /**
     * Scope that provides access to write operations on storage.
     * Only obtainable via [withWriteScope], which guarantees the storage mutex is held.
     */
    inner class StorageManagerWriteScope internal constructor() {

        /**
         * Prepare a version directory by linking files from CAS.
         *
         * For each file in the manifest that applies to the given platform:
         * 1. Look up the file in CAS by hash
         * 2. Create parent directories in the version directory
         * 3. Create a link from version/{path} to CAS/{hash}
         *    - On macOS/Linux: symlink (preferred as they don't modify the CAS file's inode)
         *    - On Windows: hard link (symlinks require elevated permissions)
         *
         * This operation is idempotent - if the version directory already exists with valid links,
         * those files are skipped. Invalid or broken links are recreated.
         *
         * Completeness is guaranteed by the caller saving manifest.json after this method returns.
         * If manifest.json exists and points to version N, that version is fully prepared.
         *
         * @param manifest The bundle manifest describing all files
         * @param platform The target platform to filter files for
         * @throws IllegalStateException if a required file is missing from CAS or linking fails
         */
        suspend fun prepareVersion(manifest: BundleManifest, platform: Platform) {
            ensureDirectoriesExist()
            val versionDir = versionsDir.resolve(manifest.buildNumber.toString())

            withContext(Dispatchers.IO) {
                Files.createDirectories(versionDir)
            }

            // Only prepare files that apply to this platform
            val platformFiles = manifest.filesForPlatform(platform)
            for (file in platformFiles) {
                val sourcePath = contentStore.getPath(file.hash)
                    ?: throw IllegalStateException("File not in CAS: ${file.hash} (${file.path})")

                val destPath = versionDir.resolve(file.path)

                // Skip if file already exists AND is a valid link to the CAS file
                val needsLink = withContext(Dispatchers.IO) {
                    !Files.exists(destPath) || !isSameFile(destPath, sourcePath)
                }
                if (!needsLink) continue

                withContext(Dispatchers.IO) {
                    Files.createDirectories(destPath.parent)
                    Files.deleteIfExists(destPath)
                    createLink(destPath, sourcePath)
                }
            }
        }

        /**
         * Save the current manifest to disk.
         */
        suspend fun saveManifest(manifestJson: String) {
            withContext(Dispatchers.IO) {
                Files.createDirectories(bundleDir)
                Files.writeString(manifestPath, manifestJson)
            }
        }

        /**
         * Delete a version directory and all its contents.
         */
        suspend fun deleteVersionDirectory(buildNumber: Long) {
            withContext(Dispatchers.IO) {
                val versionDir = versionsDir.resolve(buildNumber.toString())
                if (!Files.exists(versionDir)) return@withContext

                Files.walk(versionDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.delete(it) }
            }
        }

        /**
         * Clean up the temp directory.
         */
        suspend fun cleanupTemp() {
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
     * Ensure required directories exist.
     */
    private suspend fun ensureDirectoriesExist() = withContext(Dispatchers.IO) {
        Files.createDirectories(versionsDir)
        Files.createDirectories(tempDir)
    }

    /**
     * Check if a version directory exists.
     *
     * Note: The presence of a version directory alone does not guarantee completeness.
     * Completeness is guaranteed by the manifest.json pointing to this version -
     * manifest.json is only saved after prepareVersion() completes successfully.
     */
    suspend fun hasVersion(buildNumber: Long): Boolean = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        Files.exists(versionDir)
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
     * Get the current build number from the saved manifest.
     *
     * @return The build number from manifest.json, or null if no manifest exists
     */
    suspend fun getCurrentBuildNumber(): Long? = withContext(Dispatchers.IO) {
        val manifestJson = loadManifest() ?: return@withContext null
        try {
            Json.parseToJsonElement(manifestJson)
                .jsonObject["buildNumber"]
                ?.jsonPrimitive
                ?.longOrNull
        } catch (e: Exception) {
            null
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
     * Get the size of a CAS file.
     */
    suspend fun getCasFileSize(hash: BundleFileHash): Long = withContext(Dispatchers.IO) {
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
     * Verify all files in a version match their expected hashes.
     *
     * @param manifest The manifest to verify against
     * @param platform The target platform to filter files for
     * @return List of files that failed verification (empty if all passed)
     */
    suspend fun verifyVersion(manifest: BundleManifest, platform: Platform): List<VerificationFailure> {
        val versionDir = versionsDir.resolve(manifest.buildNumber.toString())

        val filesToVerify = manifest.filesForPlatform(platform).map { file ->
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
