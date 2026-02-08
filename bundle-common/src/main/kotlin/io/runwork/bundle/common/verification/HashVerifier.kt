package io.runwork.bundle.common.verification

import io.runwork.bundle.common.manifest.BundleFileHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.ForwardingSource
import okio.HashingSource
import okio.Path.Companion.toOkioPath
import okio.Source
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
     * @return SHA-256 hash as BundleFileHash
     */
    suspend fun computeHash(path: Path): BundleFileHash = withContext(Dispatchers.IO) {
        computeHashSync(path)
    }

    /**
     * Compute the SHA-256 hash of a file with chunk-level progress reporting.
     *
     * @param path Path to the file
     * @param onBytesRead Called after each chunk with the number of bytes just read (delta, not cumulative)
     * @return SHA-256 hash as BundleFileHash
     */
    suspend fun computeHashWithProgress(
        path: Path,
        onBytesRead: (Long) -> Unit,
    ): BundleFileHash = withContext(Dispatchers.IO) {
        val baseSource = FileSystem.SYSTEM.source(path.toOkioPath())
        val progressSource = ProgressSource(baseSource, onBytesRead)
        HashingSource.sha256(progressSource).use { hashingSource ->
            hashingSource.buffer().readAll(blackholeSink())
            BundleFileHash("sha256", hashingSource.hash.hex())
        }
    }

    /**
     * Compute the SHA-256 hash of a file (synchronous version).
     *
     * Use this when calling from non-coroutine contexts (e.g., Gradle tasks).
     *
     * @param path Path to the file
     * @return SHA-256 hash as BundleFileHash
     */
    fun computeHashSync(path: Path): BundleFileHash {
        HashingSource.sha256(FileSystem.SYSTEM.source(path.toOkioPath())).use { hashingSource ->
            hashingSource.buffer().readAll(blackholeSink())
            return BundleFileHash("sha256", hashingSource.hash.hex())
        }
    }

    /**
     * Compute the SHA-256 hash of a byte array.
     *
     * @param data The data to hash
     * @return SHA-256 hash as BundleFileHash
     */
    fun computeHash(data: ByteArray): BundleFileHash {
        return BundleFileHash("sha256", data.toByteString().sha256().hex())
    }

    /**
     * Verify that a file matches the expected hash.
     *
     * @param path Path to the file
     * @param expectedHash Expected hash
     * @return true if the hash matches, false otherwise
     */
    suspend fun verify(path: Path, expectedHash: BundleFileHash): Boolean {
        if (!withContext(Dispatchers.IO) { Files.exists(path) }) return false
        return computeHash(path) == expectedHash
    }

    /**
     * Verify multiple files concurrently with limited parallelism.
     *
     * @param files List of (path, expectedHash) pairs to verify
     * @param parallelism Maximum concurrent file reads (default 5)
     * @return List of verification results
     */
    suspend fun verifyFilesConcurrently(
        files: List<Pair<Path, BundleFileHash>>,
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
                                actualHash == expectedHash
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * Source wrapper that reports bytes read to a callback.
 */
private class ProgressSource(
    source: Source,
    private val onBytesRead: (Long) -> Unit,
) : ForwardingSource(source) {
    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read != -1L) {
            onBytesRead(read)
        }
        return read
    }
}

/**
 * Result of hash verification for a single file.
 */
data class HashVerificationResult(
    /** Path to the verified file */
    val path: Path,
    /** Expected hash */
    val expectedHash: BundleFileHash,
    /** Actual hash (null if file missing) */
    val actualHash: BundleFileHash?,
    /** Whether verification succeeded */
    val success: Boolean
)
