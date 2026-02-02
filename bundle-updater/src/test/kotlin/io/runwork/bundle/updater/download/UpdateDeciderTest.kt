package io.runwork.bundle.updater.download

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.updater.TestFixtures
import io.runwork.bundle.updater.storage.StorageManager
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import java.nio.file.Path

class UpdateDeciderTest {

    private lateinit var tempDir: Path
    private lateinit var storageManager: StorageManager
    private val platform = Platform.fromString("macos-arm64")

    // Overhead per HTTP request (50KB as defined in UpdateDecider)
    private val httpOverhead = 50_000L

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("update-decider-test")
        storageManager = StorageManager(tempDir)
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun decide_returnsNoDownloadNeeded_allFilesExist() = runTest {
        // Store files in CAS first
        val content1 = "Content 1"
        val content2 = "Content 2"
        val hash1 = TestFixtures.computeHash(content1.toByteArray())
        val hash2 = TestFixtures.computeHash(content2.toByteArray())

        val tempFile1 = TestFixtures.createTestFile(tempDir, "temp1.txt", content1)
        val tempFile2 = TestFixtures.createTestFile(tempDir, "temp2.txt", content2)
        storageManager.contentStore.store(tempFile1)
        storageManager.contentStore.store(tempFile2)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "file1.txt", hash = hash1, size = content1.length.toLong()),
                BundleFile(path = "file2.txt", hash = hash2, size = content2.length.toLong())
            )
        )

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        assertIs<DownloadStrategy.NoDownloadNeeded>(strategy)
    }

    @Test
    fun decide_returnsFullBundle_manySmallFilesMissing() = runTest {
        // No files in CAS, manifest has many small files
        // Each file: 1KB, 10 files missing
        // Incremental = 10KB data + 10 * 50KB overhead = 510KB
        // Full bundle = 10KB
        // Full bundle wins because overhead makes incremental larger
        val files = (1..10).map { i ->
            BundleFile(
                path = "file$i.jar",
                hash = "sha256:${i.toString().padStart(64, '0')}",
                size = 1000L, // 1KB
            )
        }

        val manifest = TestFixtures.createTestManifest(files = files)

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        assertIs<DownloadStrategy.FullBundle>(strategy)
        assertEquals(manifest.files.size, strategy.fileCount)
    }

    @Test
    fun decide_returnsIncremental_largeTotalSmallMissing() = runTest {
        // For incremental to win:
        // effectiveIncremental = missingSize + numMissing * 50KB < totalSize
        //
        // Setup: 9 large existing files (1MB each = 9MB total) + 1 small missing (100KB)
        // Total = 9MB + 100KB = ~9.1MB
        // Incremental = 100KB + 50KB overhead = 150KB
        // 150KB < 9.1MB, so incremental wins
        val existingContent = "A".repeat(1_000_000) // 1MB each
        val existingHash = TestFixtures.computeHash(existingContent.toByteArray())

        repeat(9) {
            val tempFile = TestFixtures.createTestFile(tempDir, "temp$it.txt", existingContent)
            storageManager.contentStore.store(tempFile)
        }

        val existingFiles = (1..9).map { i ->
            BundleFile(
                path = "file$i.txt",
                hash = existingHash,
                size = existingContent.length.toLong(), // 1MB each
            )
        }
        val missingFile = BundleFile(
            path = "new-file.txt",
            hash = "sha256:0000000000000000000000000000000000000000000000000000000000000001",
            size = 100_000L, // 100KB
        )

        val manifest = TestFixtures.createTestManifest(files = existingFiles + listOf(missingFile))

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        // 100KB + 50KB = 150KB << 9.1MB
        assertIs<DownloadStrategy.Incremental>(strategy)
        assertEquals(1, strategy.files.size)
    }

    @Test
    fun decide_comparesEffectiveSize_picksSmallerOption() = runTest {
        // Store 6 files, need 4 files
        // Each existing file: 500KB, each missing: 500KB
        // Total = 10 * 500KB = 5MB
        // Incremental data = 4 * 500KB = 2MB
        // Incremental overhead = 4 * 50KB = 200KB
        // Effective incremental = 2MB + 200KB = 2.2MB
        // 2.2MB < 5MB, so incremental wins
        val existingContent = "A".repeat(500_000) // 500KB
        val existingHash = TestFixtures.computeHash(existingContent.toByteArray())

        repeat(6) {
            val tempFile = TestFixtures.createTestFile(tempDir, "temp$it.txt", existingContent)
            storageManager.contentStore.store(tempFile)
        }

        val existingFiles = (1..6).map { i ->
            BundleFile(
                path = "existing$i.txt",
                hash = existingHash,
                size = existingContent.length.toLong(),
            )
        }

        val missingFiles = (1..4).map { i ->
            BundleFile(
                path = "missing$i.bin",
                hash = "sha256:${i.toString().padStart(64, '0')}",
                size = 500_000L, // 500KB each
            )
        }

        val manifest = TestFixtures.createTestManifest(files = existingFiles + missingFiles)

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        // Effective incremental = 2MB + 200KB = 2.2MB < 5MB full
        assertIs<DownloadStrategy.Incremental>(strategy)
    }

    @Test
    fun decide_incrementalStrategy_listsOnlyMissingFiles() = runTest {
        // Store large existing files so incremental wins
        // 3 existing files at 1MB each = 3MB
        // 1 missing file at 100KB
        // Total = 3.1MB
        // Incremental = 100KB + 50KB = 150KB << 3.1MB
        val existingContent = "A".repeat(1_000_000) // 1MB
        val existingHash = TestFixtures.computeHash(existingContent.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", existingContent)
        storageManager.contentStore.store(tempFile)

        val missingHash = "sha256:0000000000000000000000000000000000000000000000000000000000000001"

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(path = "existing.txt", hash = existingHash, size = existingContent.length.toLong()),
                BundleFile(path = "existing2.txt", hash = existingHash, size = existingContent.length.toLong()),
                BundleFile(path = "existing3.txt", hash = existingHash, size = existingContent.length.toLong()),
                BundleFile(path = "missing.txt", hash = missingHash, size = 100_000L) // 100KB
            )
        )

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        assertIs<DownloadStrategy.Incremental>(strategy)
        assertEquals(1, strategy.files.size)
        assertEquals("missing.txt", strategy.files[0].path)
        assertEquals(missingHash, strategy.files[0].hash)
    }

    @Test
    fun decide_fullBundleStrategy_hasCorrectTotalSize() = runTest {
        // All small files missing - overhead makes full bundle better
        // 3 files at 1KB each = 3KB total
        // Incremental = 3KB + 3 * 50KB = 153KB
        // Full = 3KB
        // Full wins
        val files = listOf(
            BundleFile(path = "file1.jar", hash = "sha256:0000000000000000000000000000000000000000000000000000000000000001", size = 1000L),
            BundleFile(path = "file2.jar", hash = "sha256:0000000000000000000000000000000000000000000000000000000000000002", size = 2000L),
            BundleFile(path = "file3.jar", hash = "sha256:0000000000000000000000000000000000000000000000000000000000000003", size = 3000L)
        )

        val manifest = TestFixtures.createTestManifest(files = files)

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        assertIs<DownloadStrategy.FullBundle>(strategy)
        assertEquals(manifest.totalSizeForPlatform(platform), strategy.totalSize)
        assertEquals(3, strategy.fileCount)
    }

    @Test
    fun decide_emptyManifest_returnsNoDownloadNeeded() = runTest {
        val manifest = TestFixtures.createTestManifest(files = emptyList())

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        assertIs<DownloadStrategy.NoDownloadNeeded>(strategy)
    }

    @Test
    fun decide_incrementalStrategy_hasCorrectTotalSize() = runTest {
        // Store large existing files so incremental wins
        // 5 existing files at 1MB = 5MB
        // 1 missing file at 100KB
        // Total = 5.1MB
        // Incremental = 100KB + 50KB = 150KB << 5.1MB
        val existingContent = "A".repeat(1_000_000) // 1MB each
        val existingHash = TestFixtures.computeHash(existingContent.toByteArray())
        repeat(5) {
            val tempFile = TestFixtures.createTestFile(tempDir, "temp$it.txt", existingContent)
            storageManager.contentStore.store(tempFile)
        }

        val existingFiles = (1..5).map { i ->
            BundleFile(path = "existing$i.txt", hash = existingHash, size = existingContent.length.toLong())
        }

        val missingFile = BundleFile(
            path = "missing.txt",
            hash = "sha256:0000000000000000000000000000000000000000000000000000000000000001",
            size = 100_000L, // 100KB
        )

        val manifest = TestFixtures.createTestManifest(files = existingFiles + missingFile)

        val strategy = UpdateDecider.decide(manifest, platform, storageManager.contentStore)

        assertIs<DownloadStrategy.Incremental>(strategy)
        assertEquals(100_000L, strategy.totalSize)
    }
}
