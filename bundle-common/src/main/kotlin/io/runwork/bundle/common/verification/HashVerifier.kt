package io.runwork.bundle.common.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSource
import okio.Path.Companion.toOkioPath
import okio.blackholeSink
import okio.buffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Utility for computing and verifying SHA-256 hashes.
 */
object HashVerifier {

    /**
     * Compute the SHA-256 hash of a file (suspending version).
     *
     * Offloads I/O to the IO dispatcher for use in coroutine contexts.
     *
     * @param path Path to the file
     * @return SHA-256 hash prefixed with "sha256:"
     */
    suspend fun computeHash(path: Path): String = withContext(Dispatchers.IO) {
        computeHashSync(path)
    }

    /**
     * Compute the SHA-256 hash of a file (synchronous version).
     *
     * Use this when calling from non-coroutine contexts (e.g., Gradle tasks).
     *
     * @param path Path to the file
     * @return SHA-256 hash prefixed with "sha256:"
     */
    fun computeHashSync(path: Path): String {
        HashingSource.sha256(FileSystem.SYSTEM.source(path.toOkioPath())).use { hashingSource ->
            hashingSource.buffer().readAll(blackholeSink())
            return "sha256:" + hashingSource.hash.hex()
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
        if (!withContext(Dispatchers.IO) { Files.exists(path) }) return false
        val actualHash = computeHash(path)
        return normalizeHash(actualHash) == normalizeHash(expectedHash)
    }

    /**
     * Normalize a hash by ensuring it has the "sha256:" prefix.
     */
    fun normalizeHash(hash: String): String {
        return if (hash.startsWith("sha256:")) hash else "sha256:$hash"
    }

    /**
     * Verify multiple files concurrently with limited parallelism.
     *
     * @param files List of (path, expectedHash) pairs to verify
     * @param parallelism Maximum concurrent file reads (default 5)
     * @return List of verification results
     */
    suspend fun verifyFilesConcurrently(
        files: List<Pair<Path, String>>,
        parallelism: Int = 5
    ): List<HashVerificationResult> {
        val semaphore = Semaphore(parallelism)

        return coroutineScope {
            files.map { (path, expectedHash) ->
                async {
                    semaphore.withPermit {
                        val exists = withContext(Dispatchers.IO) { Files.exists(path) }
                        if (!exists) {
                            HashVerificationResult(path, expectedHash, null, false)
                        } else {
                            val actualHash = computeHash(path)
                            HashVerificationResult(
                                path, expectedHash, actualHash,
                                normalizeHash(actualHash) == normalizeHash(expectedHash)
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * Result of hash verification for a single file.
 */
data class HashVerificationResult(
    /** Path to the verified file */
    val path: Path,
    /** Expected SHA-256 hash */
    val expectedHash: String,
    /** Actual SHA-256 hash (null if file missing) */
    val actualHash: String?,
    /** Whether verification succeeded */
    val success: Boolean
)
