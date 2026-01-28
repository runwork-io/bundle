package io.runwork.bundle.storage

import io.runwork.bundle.verification.HashVerifier
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
     * @param hash SHA-256 hash prefixed with "sha256:" or just the hex string
     */
    suspend fun contains(hash: String): Boolean = withContext(Dispatchers.IO) {
        val fileName = hash.removePrefix("sha256:")
        Files.exists(storeDir.resolve(fileName))
    }

    /**
     * Get the path to a file by hash, or null if not present.
     *
     * @param hash SHA-256 hash prefixed with "sha256:" or just the hex string
     */
    suspend fun getPath(hash: String): Path? = withContext(Dispatchers.IO) {
        val fileName = hash.removePrefix("sha256:")
        val path = storeDir.resolve(fileName)
        if (Files.exists(path)) path else null
    }

    /**
     * Store a file from a temporary location.
     *
     * The file is moved atomically from the temp location to the store.
     * If a file with the same hash already exists, the temp file is deleted.
     *
     * @param tempFile Path to the temporary file to store
     * @return The SHA-256 hash of the file (prefixed with "sha256:")
     */
    suspend fun store(tempFile: Path): String {
        ensureDirectoryExists()
        val hash = computeHash(tempFile)
        val fileName = hash.removePrefix("sha256:")
        val destPath = storeDir.resolve(fileName)

        withContext(Dispatchers.IO) {
            if (!Files.exists(destPath)) {
                moveFile(tempFile, destPath)
            } else {
                // File already exists with this hash, delete the duplicate
                Files.delete(tempFile)
            }
        }

        return hash
    }

    /**
     * Move a file with atomic move, falling back to copy-then-delete if not supported.
     */
    private fun moveFile(source: Path, dest: Path) {
        try {
            Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            // Fallback for cross-filesystem moves or unsupported filesystems
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
            Files.delete(source)
        }
    }

    /**
     * Store a file with a known hash.
     *
     * This is more efficient when the hash is already known (e.g., from manifest).
     *
     * @param tempFile Path to the temporary file to store
     * @param expectedHash The expected SHA-256 hash (prefixed with "sha256:")
     * @return true if the hash matched and file was stored, false if hash mismatch
     */
    suspend fun storeWithHash(tempFile: Path, expectedHash: String): Boolean {
        ensureDirectoryExists()
        val actualHash = computeHash(tempFile)
        if (actualHash != expectedHash) {
            withContext(Dispatchers.IO) { Files.delete(tempFile) }
            return false
        }

        val fileName = expectedHash.removePrefix("sha256:")
        val destPath = storeDir.resolve(fileName)

        withContext(Dispatchers.IO) {
            if (!Files.exists(destPath)) {
                moveFile(tempFile, destPath)
            } else {
                Files.delete(tempFile)
            }
        }

        return true
    }

    /**
     * Verify a file's hash matches the expected value.
     *
     * @param hash The expected SHA-256 hash (prefixed with "sha256:")
     * @return true if the file exists and hash matches, false otherwise
     */
    suspend fun verify(hash: String): Boolean {
        val path = getPath(hash) ?: return false
        val actualHash = computeHash(path)
        return actualHash == hash
    }

    /**
     * Delete a file from the store.
     *
     * @param hash SHA-256 hash of the file to delete
     * @return true if the file was deleted, false if it didn't exist
     */
    suspend fun delete(hash: String): Boolean = withContext(Dispatchers.IO) {
        val fileName = hash.removePrefix("sha256:")
        val path = storeDir.resolve(fileName)
        Files.deleteIfExists(path)
    }

    /**
     * List all file hashes in the store.
     */
    suspend fun listHashes(): List<String> = withContext(Dispatchers.IO) {
        if (!Files.exists(storeDir)) return@withContext emptyList()

        Files.list(storeDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { "sha256:${it.fileName}" }
                .toList()
        }
    }

    /**
     * Compute the SHA-256 hash of a file.
     *
     * @param path Path to the file
     * @return SHA-256 hash prefixed with "sha256:"
     */
    suspend fun computeHash(path: Path): String = HashVerifier.computeHash(path)
}
