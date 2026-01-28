package io.runwork.bundle.verification

import io.runwork.bundle.TestFixtures
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
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
    fun computeHash_file_returnsCorrectHash() {
        // Known content with known hash
        val content = "Hello, World!"
        val file = TestFixtures.createTestFile(tempDir, "test.txt", content)

        val hash = HashVerifier.computeHash(file)

        // SHA-256 of "Hello, World!" is well-known
        assertEquals("sha256:dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f", hash)
    }

    @Test
    fun computeHash_byteArray_returnsCorrectHash() {
        val content = "Hello, World!".toByteArray()

        val hash = HashVerifier.computeHash(content)

        assertEquals("sha256:dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f", hash)
    }

    @Test
    fun verify_returnsTrueForMatchingHash() {
        val content = "Test content for verification"
        val file = TestFixtures.createTestFile(tempDir, "verify.txt", content)
        val expectedHash = HashVerifier.computeHash(file)

        val result = HashVerifier.verify(file, expectedHash)

        assertTrue(result)
    }

    @Test
    fun verify_returnsTrueForHashWithoutPrefix() {
        val content = "Test content"
        val file = TestFixtures.createTestFile(tempDir, "verify.txt", content)
        val expectedHash = HashVerifier.computeHash(file)
        val hashWithoutPrefix = expectedHash.removePrefix("sha256:")

        val result = HashVerifier.verify(file, hashWithoutPrefix)

        assertTrue(result)
    }

    @Test
    fun verify_returnsFalseForMismatchedHash() {
        val file = TestFixtures.createTestFile(tempDir, "verify.txt", "Original content")
        val wrongHash = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

        val result = HashVerifier.verify(file, wrongHash)

        assertFalse(result)
    }

    @Test
    fun verify_returnsFalseForMissingFile() {
        val nonExistentFile = tempDir.resolve("does-not-exist.txt")
        val someHash = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

        val result = HashVerifier.verify(nonExistentFile, someHash)

        assertFalse(result)
    }

    @Test
    fun computeHash_largeFile_streamsCorrectly() {
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
    fun computeHash_emptyFile_returnsCorrectHash() {
        val file = TestFixtures.createTestFile(tempDir, "empty.txt", ByteArray(0))

        val hash = HashVerifier.computeHash(file)

        // SHA-256 of empty content is well-known
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }
}
