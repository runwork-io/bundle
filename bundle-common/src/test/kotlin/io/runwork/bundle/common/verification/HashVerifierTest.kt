package io.runwork.bundle.common.verification

import io.runwork.bundle.common.TestFixtures
import io.runwork.bundle.common.manifest.BundleFileHash
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Path

class HashVerifierTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("hash-verifier-test")
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun computeHash_file_returnsCorrectHash() = runTest {
        // Known content with known hash
        val content = "Hello, World!"
        val file = TestFixtures.createTestFile(tempDir, "test.txt", content)

        val hash = HashVerifier.computeHash(file)

        // SHA-256 of "Hello, World!" is well-known
        assertEquals(BundleFileHash("sha256", "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"), hash)
    }

    @Test
    fun computeHash_byteArray_returnsCorrectHash() {
        val content = "Hello, World!".toByteArray()

        val hash = HashVerifier.computeHash(content)

        assertEquals(BundleFileHash("sha256", "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"), hash)
    }

    @Test
    fun verify_returnsTrueForMatchingHash() = runTest {
        val content = "Test content for verification"
        val file = TestFixtures.createTestFile(tempDir, "verify.txt", content)
        val expectedHash = HashVerifier.computeHash(file)

        val result = HashVerifier.verify(file, expectedHash)

        assertTrue(result)
    }

    @Test
    fun verify_returnsTrueForHashWithoutPrefix() = runTest {
        val content = "Test content"
        val file = TestFixtures.createTestFile(tempDir, "verify.txt", content)
        val expectedHash = HashVerifier.computeHash(file)
        val reparsed = BundleFileHash.parse(expectedHash.toString())

        val result = HashVerifier.verify(file, reparsed)

        assertTrue(result)
    }

    @Test
    fun verify_returnsFalseForMismatchedHash() = runTest {
        val file = TestFixtures.createTestFile(tempDir, "verify.txt", "Original content")
        val wrongHash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")

        val result = HashVerifier.verify(file, wrongHash)

        assertFalse(result)
    }

    @Test
    fun verify_returnsFalseForMissingFile() = runTest {
        val nonExistentFile = tempDir.resolve("does-not-exist.txt")
        val someHash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")

        val result = HashVerifier.verify(nonExistentFile, someHash)

        assertFalse(result)
    }

    @Test
    fun computeHash_largeFile_streamsCorrectly() = runTest {
        // Create a file larger than the buffer size (8KB)
        val content = ByteArray(100_000) { it.toByte() }
        val file = TestFixtures.createTestFile(tempDir, "large.bin", content)

        val hash = HashVerifier.computeHash(file)

        // Verify hash is consistent
        val hash2 = HashVerifier.computeHash(file)
        assertEquals(hash, hash2)

        // Verify via byte array method produces same result
        val hashFromBytes = HashVerifier.computeHash(content)
        assertEquals(hash, hashFromBytes)
    }

    @Test
    fun computeHashWithProgress_matchesComputeHash() = runTest {
        val content = ByteArray(100_000) { it.toByte() }
        val file = TestFixtures.createTestFile(tempDir, "progress.bin", content)

        val expectedHash = HashVerifier.computeHash(file)
        val deltas = mutableListOf<Long>()

        val hash = HashVerifier.computeHashWithProgress(file) { bytesJustRead ->
            deltas.add(bytesJustRead)
        }

        assertEquals(expectedHash, hash)
        assertEquals(content.size.toLong(), deltas.sum(), "Sum of deltas should equal file size")
        assertTrue(deltas.size > 1, "Expected multiple progress callbacks, got ${deltas.size}")
    }

    @Test
    fun computeHash_emptyFile_returnsCorrectHash() = runTest {
        val file = TestFixtures.createTestFile(tempDir, "empty.txt", ByteArray(0))

        val hash = HashVerifier.computeHash(file)

        // SHA-256 of empty content is well-known
        assertEquals(BundleFileHash("sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), hash)
    }
}
