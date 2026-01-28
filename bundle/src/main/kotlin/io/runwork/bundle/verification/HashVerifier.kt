package io.runwork.bundle.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSource
import okio.Path.Companion.toOkioPath
import okio.blackholeSink
import okio.buffer
import java.nio.file.Path

/**
 * Utility for computing and verifying SHA-256 hashes.
 */
object HashVerifier {

    /**
     * Compute the SHA-256 hash of a file.
     *
     * @param path Path to the file
     * @return SHA-256 hash prefixed with "sha256:"
     */
    suspend fun computeHash(path: Path): String = withContext(Dispatchers.IO) {
        HashingSource.sha256(FileSystem.SYSTEM.source(path.toOkioPath())).use { hashingSource ->
            hashingSource.buffer().readAll(blackholeSink())
            "sha256:" + hashingSource.hash.hex()
        }
    }

    /**
     * Compute the SHA-256 hash of a byte array.
     *
     * @param data The data to hash
     * @return SHA-256 hash prefixed with "sha256:"
     */
    fun computeHash(data: ByteArray): String {
        return "sha256:" + data.toByteString().sha256().hex()
    }

    /**
     * Verify that a file matches the expected hash.
     *
     * @param path Path to the file
     * @param expectedHash Expected SHA-256 hash (with or without "sha256:" prefix)
     * @return true if the hash matches, false otherwise
     */
    suspend fun verify(path: Path, expectedHash: String): Boolean {
        if (!withContext(Dispatchers.IO) { java.nio.file.Files.exists(path) }) return false
        val actualHash = computeHash(path)
        return normalizeHash(actualHash) == normalizeHash(expectedHash)
    }

    /**
     * Normalize a hash by ensuring it has the "sha256:" prefix.
     */
    private fun normalizeHash(hash: String): String {
        return if (hash.startsWith("sha256:")) hash else "sha256:$hash"
    }
}
