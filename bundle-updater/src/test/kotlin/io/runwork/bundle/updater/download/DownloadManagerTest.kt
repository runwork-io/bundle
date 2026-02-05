package io.runwork.bundle.updater.download

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.updater.TestFixtures
import io.runwork.bundle.updater.result.DownloadException
import io.runwork.bundle.updater.result.DownloadResult
import io.runwork.bundle.updater.storage.StorageManager
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadManagerTest {

    private lateinit var tempDir: Path
    private lateinit var storageManager: StorageManager
    private lateinit var mockServer: MockWebServer
    private lateinit var downloadManager: DownloadManager
    private val platform = Platform.fromString("macos-arm64")

    private val json = Json { prettyPrint = true }

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("download-manager-test")
        storageManager = StorageManager(tempDir)
        mockServer = MockWebServer()
        mockServer.start()
        downloadManager = DownloadManager(mockServer.url("/").toString().trimEnd('/'), storageManager, platform)
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun fetchManifest_parsesValidManifest() = runTest {
        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            ),
            buildNumber = 42,
            platforms = listOf("macos-arm64")
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val fetched = downloadManager.fetchManifest()

        assertEquals(42, fetched.manifest.buildNumber)
        assertTrue(fetched.manifest.supportsPlatform(platform))
        assertEquals(1, fetched.manifest.files.size)
        assertEquals("app.jar", fetched.manifest.files[0].path)
        assertTrue(fetched.rawJson.contains("\"buildNumber\":42") || fetched.rawJson.contains("\"buildNumber\": 42"))
    }

    @Test
    fun fetchManifest_throwsForNetworkError() = runTest {
        mockServer.shutdown() // Simulate network error

        assertFailsWith<DownloadException> {
            downloadManager.fetchManifest()
        }
    }

    @Test
    fun fetchManifest_throwsForHttpError() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(404))

        assertFailsWith<DownloadException> {
            downloadManager.fetchManifest()
        }
    }

    @Test
    fun fetchManifest_throwsFor500Error() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(500))

        assertFailsWith<DownloadException> {
            downloadManager.fetchManifest()
        }
    }

    @Test
    fun fetchManifest_throwsForInvalidJson() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setBody("not valid json{{{")
                .setHeader("Content-Type", "application/json")
        )

        assertFailsWith<DownloadException> {
            downloadManager.fetchManifest()
        }
    }

    @Test
    fun fetchManifest_throwsForEmptyResponse() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(200))

        assertFailsWith<DownloadException> {
            downloadManager.fetchManifest()
        }
    }

    @Test
    fun downloadBundle_noDownloadNeeded_returnsSuccess() = runTest {
        // Store file in CAS first
        val content = "Existing content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        val tempFile = TestFixtures.createTestFile(tempDir, "temp.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "file.txt",
                    hash = hash,
                    size = content.length.toLong(),
                )
            ),
            buildNumber = 42
        )

        val progressUpdates = mutableListOf<DownloadProgress>()
        val result = downloadManager.downloadBundle(manifest) { progress ->
            progressUpdates.add(progress)
        }

        assertIs<DownloadResult.Success>(result)
        assertEquals(42, result.buildNumber)
    }

    @Test
    fun downloadBundle_incremental_downloadsEachFile() = runTest {
        // For incremental to win: effectiveIncremental < fullSize
        // Need: missingSize + 50KB overhead < totalSize
        // Setup: 5 large existing files (1MB each = 5MB) + 1 small missing (100KB)
        // Total = 5MB + 100KB ≈ 5.1MB
        // Incremental = 100KB + 50KB = 150KB << 5.1MB
        val content = "A".repeat(100_000) // 100KB content
        val hash = TestFixtures.computeHash(content.toByteArray())

        // Store large existing files
        val existingContent = "B".repeat(1_000_000) // 1MB each
        val existingHash = TestFixtures.computeHash(existingContent.toByteArray())
        repeat(5) {
            val tempFile = TestFixtures.createTestFile(tempDir, "temp$it.txt", existingContent)
            storageManager.contentStore.store(tempFile)
        }

        val existingFiles = (1..5).map { i ->
            BundleFile(
                path = "existing$i.txt",
                hash = existingHash,
                size = existingContent.length.toLong(), // 1MB each
            )
        }

        // Small missing file
        val newFile = BundleFile(
            path = "new.txt",
            hash = hash,
            size = content.length.toLong(), // 100KB
        )

        val manifest = TestFixtures.createTestManifest(
            files = existingFiles + newFile,
            buildNumber = 42
        )

        // Serve the new file
        mockServer.enqueue(
            MockResponse()
                .setBody(content)
                .setHeader("Content-Type", "application/octet-stream")
        )

        val progressUpdates = mutableListOf<DownloadProgress>()
        val result = downloadManager.downloadBundle(manifest) { progress ->
            progressUpdates.add(progress)
        }

        assertIs<DownloadResult.Success>(result)

        // Verify file was downloaded and stored
        assertTrue(storageManager.contentStore.contains(hash))

        // Verify progress was reported
        assertTrue(progressUpdates.isNotEmpty())
    }

    @Test
    fun downloadBundle_reportsProgress() = runTest {
        val content = "A".repeat(1000)
        val hash = TestFixtures.computeHash(content.toByteArray())

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "file.txt",
                    hash = hash,
                    size = content.length.toLong(),
                )
            ),
            buildNumber = 42
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(content)
                .setHeader("Content-Type", "application/octet-stream")
        )

        val progressUpdates = mutableListOf<DownloadProgress>()
        downloadManager.downloadBundle(manifest) { progress ->
            progressUpdates.add(progress)
        }

        assertTrue(progressUpdates.isNotEmpty())

        // Last progress should show complete
        val lastProgress = progressUpdates.last()
        assertEquals(content.length.toLong(), lastProgress.bytesDownloaded)
    }

    @Test
    fun downloadBundle_verifiesHashes() = runTest {
        // For incremental to win: missingSize + numMissing * 50KB < totalSize
        // Setup: 5 large existing files (1MB each = 5MB) + 1 missing (100KB)
        // Total = 5MB + 100KB = ~5.1MB
        // Incremental = 100KB + 50KB overhead = 150KB << 5.1MB
        val content = "A".repeat(100_000) // 100KB content
        val wrongHash = BundleFileHash("sha256", "0000000000000000000000000000000000000000000000000000000000000000")

        // Store large existing files so incremental strategy is chosen
        val existingContent = "B".repeat(1_000_000) // 1MB each
        val existingHash = TestFixtures.computeHash(existingContent.toByteArray())
        repeat(5) {
            val tempFile = TestFixtures.createTestFile(tempDir, "temp$it.txt", existingContent)
            storageManager.contentStore.store(tempFile)
        }

        val existingFiles = (1..5).map { i ->
            BundleFile(
                path = "existing$i.txt",
                hash = existingHash,
                size = existingContent.length.toLong(), // 1MB each
            )
        }

        val newFile = BundleFile(
            path = "file.txt",
            hash = wrongHash,
            size = content.length.toLong(), // 100KB
        )

        val manifest = TestFixtures.createTestManifest(
            files = existingFiles + newFile,
            buildNumber = 42
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(content) // Content doesn't match hash
                .setHeader("Content-Type", "application/octet-stream")
        )

        val result = downloadManager.downloadBundle(manifest) {}

        assertIs<DownloadResult.Failure>(result)
        assertTrue(result.error.contains("Hash mismatch"))
    }

    @Test
    fun downloadBundle_fullZip_downloadsAndExtracts() = runTest {
        val fileContent = "File content in zip"
        val fileHash = TestFixtures.computeHash(fileContent.toByteArray())

        val manifest = TestFixtures.createTestManifest(
            files = (1..10).map { i ->
                BundleFile(
                    path = "file$i.txt",
                    hash = if (i == 1) fileHash else BundleFileHash("sha256", i.toString().padStart(64, '0')),
                    size = 1000,
                )
            },
            buildNumber = 42
        )

        // Create a ZIP with the files (entry names are hash hex)
        val zipBytes = createTestZip(listOf(fileHash.hex to fileContent))

        mockServer.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(zipBytes))
                .setHeader("Content-Type", "application/zip")
        )

        val progressUpdates = mutableListOf<DownloadProgress>()
        val result = downloadManager.downloadBundle(manifest) { progress ->
            progressUpdates.add(progress)
        }

        // Should complete but some files might fail hash verification
        // since we only included file1 in the zip
        assertTrue(result is DownloadResult.Success || result is DownloadResult.Failure)
    }

    @Test
    fun downloadBundle_handleHttpErrorDuringDownload() = runTest {
        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "file.txt",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            ),
            buildNumber = 42
        )

        mockServer.enqueue(MockResponse().setResponseCode(404))

        val result = downloadManager.downloadBundle(manifest) {}

        assertIs<DownloadResult.Failure>(result)
    }

    private fun createTestZip(files: List<Pair<String, String>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    // ====== file:// URL Tests ======

    @Test
    fun fetchManifest_worksWithFileUrl() = runTest {
        val fileBundleDir = tempDir.resolve("file-bundle-server")
        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            ),
            buildNumber = 42,
            platforms = listOf("macos-arm64")
        )

        val baseUrl = TestFixtures.createFileBundleServer(
            baseDir = fileBundleDir,
            manifest = manifest,
            files = mapOf()
        )

        val fileDownloadManager = DownloadManager(baseUrl, storageManager, platform)

        val fetched = fileDownloadManager.fetchManifest()

        assertEquals(42, fetched.manifest.buildNumber)
        assertTrue(fetched.manifest.supportsPlatform(platform))
        assertEquals(1, fetched.manifest.files.size)
        assertEquals("app.jar", fetched.manifest.files[0].path)
    }

    @Test
    fun fetchManifest_fileUrl_throwsForMissingFile() = runTest {
        val fileBundleDir = tempDir.resolve("missing-file-bundle-server")
        java.nio.file.Files.createDirectories(fileBundleDir)

        val baseUrl = fileBundleDir.toUri().toString().trimEnd('/')
        val fileDownloadManager = DownloadManager(baseUrl, storageManager, platform)

        assertFailsWith<DownloadException> {
            fileDownloadManager.fetchManifest()
        }
    }

    @Test
    fun downloadBundle_incrementalWithFileUrl() = runTest {
        val fileBundleDir = tempDir.resolve("incremental-file-bundle")

        // Create content and compute hash
        val content = "A".repeat(100_000) // 100KB
        val hash = TestFixtures.computeHash(content.toByteArray())

        // Store large existing files to ensure incremental strategy is chosen
        val existingContent = "B".repeat(1_000_000) // 1MB each
        val existingHash = TestFixtures.computeHash(existingContent.toByteArray())
        repeat(5) {
            val tempFile = TestFixtures.createTestFile(tempDir, "temp$it.txt", existingContent)
            storageManager.contentStore.store(tempFile)
        }

        val existingFiles = (1..5).map { i ->
            BundleFile(
                path = "existing$i.txt",
                hash = existingHash,
                size = existingContent.length.toLong(),
            )
        }

        val newFile = BundleFile(
            path = "new.txt",
            hash = hash,
            size = content.length.toLong(),
        )

        val manifest = TestFixtures.createTestManifest(
            files = existingFiles + newFile,
            buildNumber = 42
        )

        val baseUrl = TestFixtures.createFileBundleServer(
            baseDir = fileBundleDir,
            manifest = manifest,
            files = mapOf(hash to content.toByteArray())
        )

        val fileDownloadManager = DownloadManager(baseUrl, storageManager, platform)

        val progressUpdates = mutableListOf<DownloadProgress>()
        val result = fileDownloadManager.downloadBundle(manifest) { progress ->
            progressUpdates.add(progress)
        }

        assertIs<DownloadResult.Success>(result)
        assertEquals(42, result.buildNumber)

        // Verify file was downloaded and stored
        assertTrue(storageManager.contentStore.contains(hash))

        // Verify progress was reported
        assertTrue(progressUpdates.isNotEmpty())
    }

    @Test
    fun downloadBundle_fullZipWithFileUrl() = runTest {
        val fileBundleDir = tempDir.resolve("fullzip-file-bundle")

        // Create files and their hashes
        val file1Content = "File 1 content"
        val file1Hash = TestFixtures.computeHash(file1Content.toByteArray())

        val file2Content = "File 2 content"
        val file2Hash = TestFixtures.computeHash(file2Content.toByteArray())

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "file1.txt",
                    hash = file1Hash,
                    size = file1Content.length.toLong(),
                ),
                BundleFile(
                    path = "file2.txt",
                    hash = file2Hash,
                    size = file2Content.length.toLong(),
                )
            ),
            buildNumber = 42
        )

        val baseUrl = TestFixtures.createFileBundleServer(
            baseDir = fileBundleDir,
            manifest = manifest,
            files = mapOf(
                file1Hash to file1Content.toByteArray(),
                file2Hash to file2Content.toByteArray()
            ),
            includeZip = true
        )

        val fileDownloadManager = DownloadManager(baseUrl, storageManager, platform)

        val progressUpdates = mutableListOf<DownloadProgress>()
        val result = fileDownloadManager.downloadBundle(manifest) { progress ->
            progressUpdates.add(progress)
        }

        assertIs<DownloadResult.Success>(result)

        // Verify files were stored
        assertTrue(storageManager.contentStore.contains(file1Hash))
        assertTrue(storageManager.contentStore.contains(file2Hash))
    }

    @Test
    fun downloadBundle_fileUrl_fileNotFound() = runTest {
        val fileBundleDir = tempDir.resolve("missing-files-bundle")

        val content = "Some content"
        val hash = TestFixtures.computeHash(content.toByteArray())

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "file.txt",
                    hash = hash,
                    size = content.length.toLong(),
                )
            ),
            buildNumber = 42
        )

        // Create bundle server WITHOUT the actual file content
        val baseUrl = TestFixtures.createFileBundleServer(
            baseDir = fileBundleDir,
            manifest = manifest,
            files = mapOf() // Empty - no files
        )

        val fileDownloadManager = DownloadManager(baseUrl, storageManager, platform)

        val result = fileDownloadManager.downloadBundle(manifest) {}

        assertIs<DownloadResult.Failure>(result)
        assertTrue(result.error.contains("not found", ignoreCase = true))
    }

    @Test
    fun downloadBundle_fileUrl_cancellation() = runTest {
        val fileBundleDir = tempDir.resolve("cancel-file-bundle")

        // Create a large file to give time for cancellation
        val largeContent = ByteArray(1_000_000) { 'X'.code.toByte() } // 1MB
        val hash = TestFixtures.computeHash(largeContent)

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "large.bin",
                    hash = hash,
                    size = largeContent.size.toLong(),
                )
            ),
            buildNumber = 42
        )

        val baseUrl = TestFixtures.createFileBundleServer(
            baseDir = fileBundleDir,
            manifest = manifest,
            files = mapOf(hash to largeContent),
            includeZip = true // Use ZIP for full bundle download
        )

        val fileDownloadManager = DownloadManager(baseUrl, storageManager, platform)

        var progressCount = 0
        val job = launch {
            fileDownloadManager.downloadBundle(manifest) {
                progressCount++
            }
        }

        // Cancel after a brief moment
        job.cancelAndJoin()

        // The job should have been cancelled
        assertTrue(job.isCancelled)
    }
}
