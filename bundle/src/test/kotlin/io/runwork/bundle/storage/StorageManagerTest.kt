package io.runwork.bundle.storage

import io.runwork.bundle.TestFixtures
import io.runwork.bundle.manifest.BundleFile
import io.runwork.bundle.manifest.FileType
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class StorageManagerTest {

    private lateinit var tempDir: Path
    private lateinit var storageManager: StorageManager

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("storage-manager-test")
        storageManager = StorageManager(tempDir)
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun hasVersion_returnsFalseForMissingVersion() = runTest {
        assertFalse(storageManager.hasVersion(42))
    }

    @Test
    fun hasVersion_returnsFalseWithoutCompleteMarker() = runTest {
        // Create version directory but no .complete marker
        val versionDir = tempDir.resolve("versions/42")
        Files.createDirectories(versionDir)
        TestFixtures.createTestFile(versionDir, "app.jar", "content")

        assertFalse(storageManager.hasVersion(42))
    }

    @Test
    fun hasVersion_returnsTrueWhenComplete() = runTest {
        // Create version directory with .complete marker
        val versionDir = tempDir.resolve("versions/42")
        Files.createDirectories(versionDir)
        Files.createFile(versionDir.resolve(".complete"))

        assertTrue(storageManager.hasVersion(42))
    }

    @Test
    fun listVersions_returnsEmptyWhenNone() = runTest {
        val versions = storageManager.listVersions()
        assertTrue(versions.isEmpty())
    }

    @Test
    fun listVersions_returnsSortedVersions() = runTest {
        // Create multiple version directories
        Files.createDirectories(tempDir.resolve("versions/10"))
        Files.createDirectories(tempDir.resolve("versions/5"))
        Files.createDirectories(tempDir.resolve("versions/20"))

        val versions = storageManager.listVersions()

        assertEquals(listOf(5L, 10L, 20L), versions)
    }

    @Test
    fun listVersions_ignoresNonNumericDirectories() = runTest {
        Files.createDirectories(tempDir.resolve("versions/42"))
        Files.createDirectories(tempDir.resolve("versions/invalid"))
        Files.createDirectories(tempDir.resolve("versions/abc123"))

        val versions = storageManager.listVersions()

        assertEquals(listOf(42L), versions)
    }

    @Test
    fun prepareVersion_createsHardLinks() = runTest {
        // Store a file in CAS first
        val content = "Test JAR content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.jar", content)
        storageManager.contentStore.store(tempFile)

        // Create manifest with that file
        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = hash,
                    size = content.length.toLong(),
                    type = FileType.JAR
                )
            ),
            buildNumber = 42
        )

        storageManager.prepareVersion(manifest)

        // Verify version directory exists with file
        val versionDir = storageManager.getVersionPath(42)
        assertTrue(Files.exists(versionDir.resolve("app.jar")))
        assertTrue(Files.exists(versionDir.resolve(".complete")))

        // Verify content is correct
        assertEquals(content, Files.readString(versionDir.resolve("app.jar")))
    }

    @Test
    fun prepareVersion_createsNestedDirectories() = runTest {
        val content = "Native library content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.dylib", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "natives/macos/libfoo.dylib",
                    hash = hash,
                    size = content.length.toLong(),
                    type = FileType.NATIVE
                )
            ),
            buildNumber = 42
        )

        storageManager.prepareVersion(manifest)

        val versionDir = storageManager.getVersionPath(42)
        assertTrue(Files.exists(versionDir.resolve("natives/macos/libfoo.dylib")))
    }

    @Test
    fun prepareVersion_createsCompleteMarker() = runTest {
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            buildNumber = 42
        )

        storageManager.prepareVersion(manifest)

        val versionDir = storageManager.getVersionPath(42)
        assertTrue(Files.exists(versionDir.resolve(".complete")))
    }

    @Test
    fun prepareVersion_throwsForMissingCasFile() = runTest {
        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "missing.jar",
                    hash = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                    size = 100,
                    type = FileType.JAR
                )
            ),
            buildNumber = 42
        )

        assertFailsWith<IllegalStateException> {
            storageManager.prepareVersion(manifest)
        }
    }

    @Test
    fun getCurrentVersion_returnsNullWhenNotSet() = runTest {
        assertNull(storageManager.getCurrentVersion())
    }

    @Test
    fun setCurrentVersion_createsPointer() = runTest {
        // Create a version first
        Files.createDirectories(tempDir.resolve("versions/42"))

        storageManager.setCurrentVersion(42)

        assertEquals(42, storageManager.getCurrentVersion())
    }

    @Test
    fun getCurrentVersion_readsFromPointer() = runTest {
        Files.createDirectories(tempDir.resolve("versions/100"))
        storageManager.setCurrentVersion(100)

        val current = storageManager.getCurrentVersion()

        assertEquals(100, current)
    }

    @Test
    fun setCurrentVersion_updatesExistingPointer() = runTest {
        Files.createDirectories(tempDir.resolve("versions/1"))
        Files.createDirectories(tempDir.resolve("versions/2"))

        storageManager.setCurrentVersion(1)
        assertEquals(1, storageManager.getCurrentVersion())

        storageManager.setCurrentVersion(2)
        assertEquals(2, storageManager.getCurrentVersion())
    }

    @Test
    fun cleanupOldVersions_keepsCurrentAndPrevious() = runTest {
        // Create multiple versions
        for (v in listOf(1L, 2L, 3L, 4L, 5L)) {
            val versionDir = tempDir.resolve("versions/$v")
            Files.createDirectories(versionDir)
            Files.createFile(versionDir.resolve(".complete"))
        }
        storageManager.setCurrentVersion(5)

        storageManager.cleanupOldVersions()

        val remaining = storageManager.listVersions()
        // Should keep 5 (current) and 4 (previous)
        assertEquals(listOf(4L, 5L), remaining)
    }

    @Test
    fun cleanupOldVersions_deletesOrphanedCasFiles() = runTest {
        // Store files in CAS
        val content1 = "Content 1"
        val content2 = "Content 2"
        val hash1 = TestFixtures.computeHash(content1.toByteArray())
        val hash2 = TestFixtures.computeHash(content2.toByteArray())

        val tempFile1 = TestFixtures.createTestFile(tempDir, "temp1.txt", content1)
        val tempFile2 = TestFixtures.createTestFile(tempDir, "temp2.txt", content2)
        storageManager.contentStore.store(tempFile1)
        storageManager.contentStore.store(tempFile2)

        // Create version that only uses content1
        val versionDir = tempDir.resolve("versions/1")
        Files.createDirectories(versionDir)
        val sourcePath = storageManager.contentStore.getPath(hash1)!!
        Files.createLink(versionDir.resolve("file.txt"), sourcePath)
        Files.createFile(versionDir.resolve(".complete"))
        storageManager.setCurrentVersion(1)

        // Verify both files exist in CAS
        assertEquals(2, storageManager.contentStore.listHashes().size)

        storageManager.cleanupOldVersions()

        // Orphaned file (content2) should be deleted
        val remainingHashes = storageManager.contentStore.listHashes()
        assertEquals(1, remainingHashes.size)
        assertTrue(storageManager.contentStore.contains(hash1))
        assertFalse(storageManager.contentStore.contains(hash2))
    }

    @Test
    fun verifyVersion_returnsEmptyForValidVersion() = runTest {
        val content = "Valid content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            buildNumber = 42
        )
        storageManager.prepareVersion(manifest)

        val failures = storageManager.verifyVersion(manifest)

        assertTrue(failures.isEmpty())
    }

    @Test
    fun verifyVersion_returnsFailuresForTamperedFiles() = runTest {
        val content = "Original content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            buildNumber = 42
        )
        storageManager.prepareVersion(manifest)

        // Tamper with the file
        val versionDir = storageManager.getVersionPath(42)
        Files.writeString(versionDir.resolve("file.txt"), "Tampered content")

        val failures = storageManager.verifyVersion(manifest)

        assertEquals(1, failures.size)
        assertEquals("file.txt", failures[0].path)
        assertEquals("Hash mismatch", failures[0].reason)
        assertNotNull(failures[0].actualHash)
    }

    @Test
    fun verifyVersion_returnsFailuresForMissingFiles() = runTest {
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            buildNumber = 42
        )
        storageManager.prepareVersion(manifest)

        // Delete the file
        val versionDir = storageManager.getVersionPath(42)
        Files.delete(versionDir.resolve("file.txt"))

        val failures = storageManager.verifyVersion(manifest)

        assertEquals(1, failures.size)
        assertEquals("file.txt", failures[0].path)
        assertEquals("File missing", failures[0].reason)
        assertNull(failures[0].actualHash)
    }

    @Test
    fun saveManifest_and_loadManifest_roundTrip() = runTest {
        val manifestJson = """{"schemaVersion":1,"buildNumber":42}"""

        storageManager.saveManifest(manifestJson)
        val loaded = storageManager.loadManifest()

        assertEquals(manifestJson, loaded)
    }

    @Test
    fun loadManifest_returnsNullWhenNotSaved() = runTest {
        val loaded = storageManager.loadManifest()
        assertNull(loaded)
    }

    @Test
    fun createTempFile_createsFileInTempDir() = runTest {
        val tempFile = storageManager.createTempFile("test")

        assertTrue(Files.exists(tempFile))
        assertTrue(tempFile.toString().contains("temp"))
    }

    @Test
    fun cleanupTemp_deletesAllTempFiles() = runTest {
        // Create some temp files
        val temp1 = storageManager.createTempFile("test1")
        val temp2 = storageManager.createTempFile("test2")

        assertTrue(Files.exists(temp1))
        assertTrue(Files.exists(temp2))

        storageManager.cleanupTemp()

        assertFalse(Files.exists(temp1))
        assertFalse(Files.exists(temp2))
    }
}
