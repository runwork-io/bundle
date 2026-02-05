package io.runwork.bundle.common.storage

import io.runwork.bundle.common.TestFixtures
import io.runwork.bundle.common.manifest.BundleFileHash
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class ContentAddressableStoreTest {

    private lateinit var tempDir: Path
    private lateinit var store: ContentAddressableStore

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("cas-test")
        store = ContentAddressableStore(tempDir.resolve("cas"))
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun store_returnsCorrectHash() = runTest {
        val content = "Test content for hashing"
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)

        val hash = store.store(tempFile)

        // Verify it's a valid SHA-256 hash with prefix
        assertEquals("sha256", hash.type)
        assertEquals(64, hash.value.length) // 64 hex chars

        // Verify hash is correct
        val expectedHash = TestFixtures.computeHash(content.toByteArray())
        assertEquals(expectedHash, hash)
    }

    @Test
    fun store_deduplicatesIdenticalFiles() = runTest {
        val content = "Duplicate content"
        val tempFile1 = TestFixtures.createTestFile(tempDir, "temp1.txt", content)
        val tempFile2 = TestFixtures.createTestFile(tempDir, "temp2.txt", content)

        val hash1 = store.store(tempFile1)
        val hash2 = store.store(tempFile2)

        // Same hash
        assertEquals(hash1, hash2)

        // Only one file in store
        assertEquals(1, store.listHashes().size)

        // Both temp files should be gone (moved or deleted)
        assertFalse(Files.exists(tempFile1))
        assertFalse(Files.exists(tempFile2))
    }

    @Test
    fun contains_returnsTrueForStoredFile() = runTest {
        val content = "Content to store"
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        val hash = store.store(tempFile)

        assertTrue(store.contains(hash))
    }

    @Test
    fun contains_returnsFalseForMissingFile() = runTest {
        val unknownHash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")

        assertFalse(store.contains(unknownHash))
    }

    @Test
    fun getPath_returnsPathForStoredFile() = runTest {
        val content = "Content for path lookup"
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        val hash = store.store(tempFile)

        val path = store.getPath(hash)

        assertNotNull(path)
        assertTrue(Files.exists(path))
        assertEquals(content, Files.readString(path))
    }

    @Test
    fun getPath_returnsNullForMissingFile() = runTest {
        val unknownHash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")

        val path = store.getPath(unknownHash)

        assertNull(path)
    }

    @Test
    fun verify_returnsTrueForValidFile() = runTest {
        val content = "Content to verify"
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        val hash = store.store(tempFile)

        assertTrue(store.verify(hash))
    }

    @Test
    fun verify_returnsFalseForCorruptedFile() = runTest {
        val content = "Original content"
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        val hash = store.store(tempFile)

        // Corrupt the file
        val storedPath = store.getPath(hash)!!
        Files.writeString(storedPath, "Corrupted content")

        assertFalse(store.verify(hash))
    }

    @Test
    fun delete_removesFile() = runTest {
        val content = "Content to delete"
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        val hash = store.store(tempFile)

        assertTrue(store.contains(hash))

        val deleted = store.delete(hash)

        assertTrue(deleted)
        assertFalse(store.contains(hash))
        assertNull(store.getPath(hash))
    }

    @Test
    fun delete_returnsFalseForMissingFile() = runTest {
        val unknownHash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")

        val deleted = store.delete(unknownHash)

        assertFalse(deleted)
    }

    @Test
    fun listHashes_returnsAllStoredHashes() = runTest {
        val content1 = "First content"
        val content2 = "Second content"
        val content3 = "Third content"

        val tempFile1 = TestFixtures.createTestFile(tempDir, "temp1.txt", content1)
        val tempFile2 = TestFixtures.createTestFile(tempDir, "temp2.txt", content2)
        val tempFile3 = TestFixtures.createTestFile(tempDir, "temp3.txt", content3)

        val hash1 = store.store(tempFile1)
        val hash2 = store.store(tempFile2)
        val hash3 = store.store(tempFile3)

        val hashes = store.listHashes()

        assertEquals(3, hashes.size)
        assertTrue(hashes.contains(hash1))
        assertTrue(hashes.contains(hash2))
        assertTrue(hashes.contains(hash3))
    }

    @Test
    fun listHashes_returnsEmptyForEmptyStore() = runTest {
        val hashes = store.listHashes()

        assertTrue(hashes.isEmpty())
    }

    @Test
    fun storeWithHash_acceptsMatchingHash() = runTest {
        val content = "Content with known hash"
        val expectedHash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)

        val result = store.storeWithHash(tempFile, expectedHash)

        assertTrue(result)
        assertTrue(store.contains(expectedHash))
        // Temp file should be moved/deleted
        assertFalse(Files.exists(tempFile))
    }

    @Test
    fun storeWithHash_rejectsWrongHash() = runTest {
        val content = "Content with wrong hash"
        val wrongHash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)

        val result = store.storeWithHash(tempFile, wrongHash)

        assertFalse(result)
        assertFalse(store.contains(wrongHash))
        // Temp file should be deleted
        assertFalse(Files.exists(tempFile))
    }

    @Test
    fun storeWithHash_deduplicatesExistingFile() = runTest {
        val content = "Deduplicated content"
        val hash = TestFixtures.computeHash(content.toByteArray())

        // Store first file
        val tempFile1 = TestFixtures.createTestFile(tempDir, "temp1.txt", content)
        store.store(tempFile1)

        // Store same content again with known hash
        val tempFile2 = TestFixtures.createTestFile(tempDir, "temp2.txt", content)
        val result = store.storeWithHash(tempFile2, hash)

        assertTrue(result)
        assertEquals(1, store.listHashes().size)
    }
}
