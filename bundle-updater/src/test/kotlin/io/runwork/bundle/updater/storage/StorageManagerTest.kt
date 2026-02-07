package io.runwork.bundle.updater.storage

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.updater.TestFixtures
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
    private val platform = Platform.fromString("macos-arm64")

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
    fun hasVersion_returnsTrueWhenDirectoryExists() = runTest {
        // Create version directory
        val versionDir = tempDir.resolve("versions/42")
        Files.createDirectories(versionDir)

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
    fun prepareVersion_createsLinks() = runTest {
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
                )
            ),
            buildNumber = 42
        )

        storageManager.withWriteScope { scope ->
            scope.prepareVersion(manifest, platform)
        }

        // Verify version directory exists with file
        val versionDir = storageManager.getVersionPath(42)
        assertTrue(Files.exists(versionDir.resolve("app.jar")))

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
                )
            ),
            buildNumber = 42
        )

        storageManager.withWriteScope { scope ->
            scope.prepareVersion(manifest, platform)
        }

        val versionDir = storageManager.getVersionPath(42)
        assertTrue(Files.exists(versionDir.resolve("natives/macos/libfoo.dylib")))
    }

    @Test
    fun prepareVersion_isIdempotent() = runTest {
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong())
            ),
            buildNumber = 42
        )

        // Call prepareVersion twice - should not throw
        storageManager.withWriteScope { scope ->
            scope.prepareVersion(manifest, platform)
            scope.prepareVersion(manifest, platform)
        }

        val versionDir = storageManager.getVersionPath(42)
        assertTrue(Files.exists(versionDir.resolve("file.txt")))
    }

    @Test
    fun prepareVersion_throwsForMissingCasFile() = runTest {
        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "missing.jar",
                    hash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000"),
                    size = 100,
                )
            ),
            buildNumber = 42
        )

        assertFailsWith<IllegalStateException> {
            storageManager.withWriteScope { scope ->
                scope.prepareVersion(manifest, platform)
            }
        }
    }

    @Test
    fun getCurrentBuildNumber_returnsNullWhenNoManifest() = runTest {
        assertNull(storageManager.getCurrentBuildNumber())
    }

    @Test
    fun getCurrentBuildNumber_returnsBuildNumberFromManifest() = runTest {
        val manifestJson = """{"schemaVersion":1,"buildNumber":42}"""
        storageManager.withWriteScope { scope ->
            scope.saveManifest(manifestJson)
        }

        assertEquals(42, storageManager.getCurrentBuildNumber())
    }

    @Test
    fun listVersions_returnsAllVersions() = runTest {
        for (v in listOf(1L, 2L, 3L)) {
            val versionDir = tempDir.resolve("versions/$v")
            Files.createDirectories(versionDir)
        }

        val versions = storageManager.listVersions()

        assertEquals(listOf(1L, 2L, 3L), versions)
    }

    @Test
    fun deleteVersionDirectory_removesVersionDir() = runTest {
        // Create a version directory with files
        val versionDir = tempDir.resolve("versions/42")
        Files.createDirectories(versionDir)
        Files.writeString(versionDir.resolve("file.txt"), "content")

        assertTrue(Files.exists(versionDir))

        storageManager.withWriteScope { scope ->
            scope.deleteVersionDirectory(42)
        }

        assertFalse(Files.exists(versionDir))
    }

    @Test
    fun verifyVersion_returnsEmptyForValidVersion() = runTest {
        val content = "Valid content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong())
            ),
            buildNumber = 42
        )
        storageManager.withWriteScope { scope ->
            scope.prepareVersion(manifest, platform)
        }

        val failures = storageManager.verifyVersion(manifest, platform)

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
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong())
            ),
            buildNumber = 42
        )
        storageManager.withWriteScope { scope ->
            scope.prepareVersion(manifest, platform)
        }

        // Tamper with the file
        val versionDir = storageManager.getVersionPath(42)
        Files.writeString(versionDir.resolve("file.txt"), "Tampered content")

        val failures = storageManager.verifyVersion(manifest, platform)

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
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong())
            ),
            buildNumber = 42
        )
        storageManager.withWriteScope { scope ->
            scope.prepareVersion(manifest, platform)
        }

        // Delete the file
        val versionDir = storageManager.getVersionPath(42)
        Files.delete(versionDir.resolve("file.txt"))

        val failures = storageManager.verifyVersion(manifest, platform)

        assertEquals(1, failures.size)
        assertEquals("file.txt", failures[0].path)
        assertEquals("File missing", failures[0].reason)
        assertNull(failures[0].actualHash)
    }

    @Test
    fun saveManifest_and_loadManifest_roundTrip() = runTest {
        val manifestJson = """{"schemaVersion":1,"buildNumber":42}"""

        storageManager.withWriteScope { scope ->
            scope.saveManifest(manifestJson)
        }
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

        storageManager.withWriteScope { scope ->
            scope.cleanupTemp()
        }

        assertFalse(Files.exists(temp1))
        assertFalse(Files.exists(temp2))
    }

}
