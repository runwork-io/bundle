package io.runwork.bundle.common.storage

import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.verification.HashVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Content-addressable storage for bundle files.
 *
 * Files are stored by their SHA-256 hash, enabling:
 * - Deduplication across versions (same file = same hash = stored once)
 * - Integrity verification (hash mismatch = corruption/tampering)
 * - Efficient incremental updates (only download files with new hashes)
 */
class ContentAddressableStore(
    private val storeDir: Path
) {
    /**
     * Ensure the store directory exists.
     */
    private suspend fun ensureDirectoryExists() = withContext(Dispatchers.IO) {
        Files.createDirectories(storeDir)
    }

    /**
     * Check if a file with the given hash exists in the store.
     *
     * @param hash The file hash
     */
    suspend fun contains(hash: BundleFileHash): Boolean = withContext(Dispatchers.IO) {
        Files.exists(storeDir.resolve(hash.hex))
    }

    /**
     * Get the path to a file by hash, or null if not present.
     *
     * @param hash The file hash
     */
    suspend fun getPath(hash: BundleFileHash): Path? = withContext(Dispatchers.IO) {
        val path = storeDir.resolve(hash.hex)
        if (Files.exists(path)) path else null
    }

    /**
     * Store a file from a temporary location.
     *
     * The file is moved atomically from the temp location to the store.
     * If a file with the same hash already exists, the temp file is deleted.
     *
     * @param tempFile Path to the temporary file to store
     * @return The hash of the file
     */
    suspend fun store(tempFile: Path): BundleFileHash {
        ensureDirectoryExists()
        val hash = computeHash(tempFile)
        val destPath = storeDir.resolve(hash.hex)

        withContext(Dispatchers.IO) {
            if (!Files.exists(destPath)) {
                Files.move(tempFile, destPath, StandardCopyOption.ATOMIC_MOVE)
            } else {
                // File already exists with this hash, delete the duplicate
                Files.delete(tempFile)
            }
        }

        return hash
    }

    /**
     * Store a file with a known hash.
     *
     * This is more efficient when the hash is already known (e.g., from manifest).
     *
     * @param tempFile Path to the temporary file to store
     * @param expectedHash The expected hash
     * @return true if the hash matched and file was stored, false if hash mismatch
     */
    suspend fun storeWithHash(tempFile: Path, expectedHash: BundleFileHash): Boolean {
        ensureDirectoryExists()
        val actualHash = computeHash(tempFile)
        if (actualHash != expectedHash) {
            withContext(Dispatchers.IO) { Files.delete(tempFile) }
            return false
        }

        val destPath = storeDir.resolve(expectedHash.hex)

        withContext(Dispatchers.IO) {
            if (!Files.exists(destPath)) {
                Files.move(tempFile, destPath, StandardCopyOption.ATOMIC_MOVE)
            } else {
                Files.delete(tempFile)
            }
        }

        return true
    }

    /**
     * Verify a file's hash matches the expected value.
     *
     * @param hash The expected hash
     * @return true if the file exists and hash matches, false otherwise
     */
    suspend fun verify(hash: BundleFileHash): Boolean {
        val path = getPath(hash) ?: return false
        val actualHash = computeHash(path)
        return actualHash == hash
    }

    /**
     * Delete a file from the store.
     *
     * @param hash Hash of the file to delete
     * @return true if the file was deleted, false if it didn't exist
     */
    suspend fun delete(hash: BundleFileHash): Boolean = withContext(Dispatchers.IO) {
        val path = storeDir.resolve(hash.hex)
        Files.deleteIfExists(path)
    }

    /**
     * List all file hashes in the store.
     */
    suspend fun listHashes(): List<BundleFileHash> = withContext(Dispatchers.IO) {
        if (!Files.exists(storeDir)) return@withContext listOf()

        Files.list(storeDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                // All files in the CAS are stored via SHA-256 hashing, so we can assume the algorithm
                .map { BundleFileHash("sha256", it.fileName.toString()) }
                .toList()
        }
    }

    /**
     * Compute the SHA-256 hash of a file.
     *
     * @param path Path to the file
     * @return The file hash
     */
    suspend fun computeHash(path: Path): BundleFileHash = HashVerifier.computeHash(path)
}
