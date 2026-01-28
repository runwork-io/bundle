package io.runwork.bundle.storage

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
    init {
        Files.createDirectories(storeDir)
    }

    /**
     * Check if a file with the given hash exists in the store.
     *
     * @param hash SHA-256 hash prefixed with "sha256:" or just the hex string
     */
    fun contains(hash: String): Boolean {
        val fileName = hash.removePrefix("sha256:")
        return Files.exists(storeDir.resolve(fileName))
    }

    /**
     * Get the path to a file by hash, or null if not present.
     *
     * @param hash SHA-256 hash prefixed with "sha256:" or just the hex string
     */
    fun getPath(hash: String): Path? {
        val fileName = hash.removePrefix("sha256:")
        val path = storeDir.resolve(fileName)
        return if (Files.exists(path)) path else null
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
    fun store(tempFile: Path): String {
        val hash = computeHash(tempFile)
        val fileName = hash.removePrefix("sha256:")
        val destPath = storeDir.resolve(fileName)

        if (!Files.exists(destPath)) {
            moveFile(tempFile, destPath)
        } else {
            // File already exists with this hash, delete the duplicate
            Files.delete(tempFile)
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
    fun storeWithHash(tempFile: Path, expectedHash: String): Boolean {
        val actualHash = computeHash(tempFile)
        if (actualHash != expectedHash) {
            Files.delete(tempFile)
            return false
        }

        val fileName = expectedHash.removePrefix("sha256:")
        val destPath = storeDir.resolve(fileName)

        if (!Files.exists(destPath)) {
            moveFile(tempFile, destPath)
        } else {
            Files.delete(tempFile)
        }

        return true
    }

    /**
     * Verify a file's hash matches the expected value.
     *
     * @param hash The expected SHA-256 hash (prefixed with "sha256:")
     * @return true if the file exists and hash matches, false otherwise
     */
    fun verify(hash: String): Boolean {
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
    fun delete(hash: String): Boolean {
        val fileName = hash.removePrefix("sha256:")
        val path = storeDir.resolve(fileName)
        return Files.deleteIfExists(path)
    }

    /**
     * List all file hashes in the store.
     */
    fun listHashes(): List<String> {
        if (!Files.exists(storeDir)) return emptyList()

        return Files.list(storeDir).use { stream ->
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
    fun computeHash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return "sha256:" + digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
