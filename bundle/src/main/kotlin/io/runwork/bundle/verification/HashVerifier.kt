package io.runwork.bundle.verification

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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

    /**
     * Compute the SHA-256 hash of a byte array.
     *
     * @param data The data to hash
     * @return SHA-256 hash prefixed with "sha256:"
     */
    fun computeHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data)
        return "sha256:" + digest.digest().toHexString()
    }

    /**
     * Verify that a file matches the expected hash.
     *
     * @param path Path to the file
     * @param expectedHash Expected SHA-256 hash (with or without "sha256:" prefix)
     * @return true if the hash matches, false otherwise
     */
    fun verify(path: Path, expectedHash: String): Boolean {
        if (!Files.exists(path)) return false
        val actualHash = computeHash(path)
        return normalizeHash(actualHash) == normalizeHash(expectedHash)
    }

    /**
     * Normalize a hash by ensuring it has the "sha256:" prefix.
     */
    private fun normalizeHash(hash: String): String {
        return if (hash.startsWith("sha256:")) hash else "sha256:$hash"
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
