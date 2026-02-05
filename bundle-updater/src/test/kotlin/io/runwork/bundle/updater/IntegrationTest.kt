package io.runwork.bundle.updater

import io.runwork.bundle.bootstrap.BundleBootstrap
import io.runwork.bundle.bootstrap.BundleBootstrapConfig
import io.runwork.bundle.bootstrap.BundleValidationResult
import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.verification.SignatureVerifier
import io.runwork.bundle.creator.BundlePackager
import io.runwork.bundle.creator.BundleManifestBuilder
import io.runwork.bundle.creator.BundleManifestSigner
import io.runwork.bundle.updater.download.DownloadProgress
import io.runwork.bundle.updater.result.DownloadResult
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Timeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests exercising end-to-end flows for all 4 supported use cases.
 *
 * Uses MockWebServer for HTTP simulation and real file system operations.
 */
class IntegrationTest {

    private lateinit var mockServer: MockWebServer
    private val json = Json { prettyPrint = true }
    private val tempDirs = mutableListOf<Path>()

    @BeforeTest
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
        tempDirs.forEach { TestFixtures.deleteRecursively(it) }
    }

    private fun createTempDir(prefix: String): Path {
        val dir = TestFixtures.createTempDir(prefix)
        tempDirs.add(dir)
        return dir
    }

    // ========== USE CASE 1: Shell First-Run (No Bundle Exists) ==========

    @Test
    @Timeout(60)
    fun shellFirstRun_downloadsBundleAndValidates() = runBlocking {
        // Setup: Empty app data directory, MockWebServer with manifest + bundle.zip
        val appDataDir = createTempDir("integration-first-run")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Create test files and signed manifest
        val jarContent = "test-jar-content".toByteArray()
        val files = listOf(TestFixtures.createBundleFile("app.jar", jarContent))
        val manifest = TestFixtures.createSignedManifest(
            files = files,
            signer = keyPair.signer,
            buildNumber = 100,
        )

        // Setup mock server with bundle.zip (no manifest needed since we pass manifest directly)
        mockServer.enqueue(
            MockResponse()
                .setBody(createBundleZip(files, mapOf("app.jar" to jarContent)))
                .setHeader("Content-Type", "application/zip")
        )

        val bootstrapConfig = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
            publicKey = keyPair.publicKeyBase64,
            shellVersion = 1,
            platform = Platform.fromString("macos-arm64"),
            mainClass = "io.test.TestMain",
        )
        val bootstrap = BundleBootstrap(bootstrapConfig)

        // Step 1: Validate returns NoBundleExists
        val initialResult = bootstrap.validate()
        assertIs<BundleValidationResult.NoBundleExists>(initialResult)

        // Step 2: Download bundle using DownloadManager directly to bypass BundleUpdater complexity
        val storageManager = io.runwork.bundle.updater.storage.StorageManager(appDataDir)
        val platform = Platform.fromString("macos-arm64")
        val downloadManager = io.runwork.bundle.updater.download.DownloadManager(
            mockServer.url("/").toString().trimEnd('/'),
            storageManager,
            platform
        )

        val progressUpdates = mutableListOf<DownloadProgress>()
        val downloadResult = downloadManager.downloadBundle(manifest) { progressUpdates.add(it) }

        // Debug: print error if download failed
        if (downloadResult is DownloadResult.Failure) {
            println("Download failed: ${downloadResult.error}")
            downloadResult.cause?.printStackTrace()
        }
        assertIs<DownloadResult.Success>(downloadResult)

        // Manually finalize: prepare version and save manifest
        storageManager.prepareVersion(manifest, platform)
        storageManager.saveManifest(json.encodeToString(manifest))

        // Step 3: Validate succeeds after download
        val validResult = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(validResult)
        assertEquals(100, validResult.manifest.buildNumber)

        // Verify storage structure
        assertTrue(Files.exists(appDataDir.resolve("manifest.json")))
        assertTrue(Files.exists(appDataDir.resolve("versions/100/app.jar")))

        downloadManager.close()
    }

    // ========== USE CASE 2: Bundle Self-Update (Running Bundle Updates Itself) ==========

    @Test
    @Timeout(60)
    fun bundleSelfUpdate_detectsAndDownloadsUpdate() = runBlocking {
        // Setup: App data directory with existing bundle (build 100)
        val appDataDir = createTempDir("integration-self-update")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Create initial bundle (build 100)
        val initialContent = "v1-content".toByteArray()
        val initialFiles = listOf(TestFixtures.createBundleFile("app.jar", initialContent))
        val initialManifest = TestFixtures.createSignedManifest(
            files = initialFiles,
            signer = keyPair.signer,
            buildNumber = 100,
        )
        setupExistingBundle(appDataDir, initialManifest, mapOf("app.jar" to initialContent))

        // Verify initial bundle exists
        val bootstrapConfig = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
            publicKey = keyPair.publicKeyBase64,
            shellVersion = 1,
            platform = Platform.fromString("macos-arm64"),
            mainClass = "io.test.TestMain",
        )
        val bootstrap = BundleBootstrap(bootstrapConfig)
        val initialResult = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(initialResult)
        assertEquals(100, initialResult.manifest.buildNumber)

        // Create updated bundle (build 200)
        val updatedContent = "v2-content".toByteArray()
        val updatedFiles = listOf(TestFixtures.createBundleFile("app.jar", updatedContent))
        val updatedManifest = TestFixtures.createSignedManifest(
            files = updatedFiles,
            signer = keyPair.signer,
            buildNumber = 200,
        )

        // Mock server: 1st = fetch manifest, 2nd = bundle.zip for full download
        // (FullBundle strategy is chosen since all files are new)
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(updatedManifest))
                .setHeader("Content-Type", "application/json")
        )
        mockServer.enqueue(
            MockResponse()
                .setBody(createBundleZip(updatedFiles, mapOf("app.jar" to updatedContent)))
                .setHeader("Content-Type", "application/zip")
        )

        // Use DownloadManager directly to simulate bundle self-update
        val storageManager = io.runwork.bundle.updater.storage.StorageManager(appDataDir)
        val platform = Platform.fromString("macos-arm64")
        val downloadManager = io.runwork.bundle.updater.download.DownloadManager(
            mockServer.url("/").toString().trimEnd('/'),
            storageManager,
            platform
        )

        // Step 1: Fetch manifest to check for update
        val fetchedManifest = downloadManager.fetchManifest()
        assertEquals(200, fetchedManifest.buildNumber)
        assertTrue(fetchedManifest.buildNumber > 100, "Update should be newer than current")

        // Step 2: Download the update (incremental - only new file)
        val downloadResult = downloadManager.downloadBundle(fetchedManifest) {}

        // Debug: print download result
        if (downloadResult is DownloadResult.Failure) {
            println("Download failed: ${downloadResult.error}")
            downloadResult.cause?.printStackTrace()
        }
        assertIs<DownloadResult.Success>(downloadResult)

        // Step 3: Prepare new version
        storageManager.prepareVersion(fetchedManifest, platform)
        storageManager.saveManifest(json.encodeToString(fetchedManifest))

        // Verify new version is prepared
        assertTrue(Files.exists(appDataDir.resolve("versions/200/app.jar")))

        // Verify new version validates
        val newResult = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(newResult)
        assertEquals(200, newResult.manifest.buildNumber)

        downloadManager.close()
    }

    // ========== USE CASE 3: Bundle Creator (CI Creates Bundle) ==========

    @Test
    fun bundleCreator_createsSignedBundle() = runBlocking {
        val inputDir = createTempDir("integration-creator-input")
        val outputDir = createTempDir("integration-creator-output")
        val targetPlatforms = listOf("macos-arm64")

        // Create input files
        TestFixtures.createTestFile(inputDir, "app.jar", "jar-content")
        TestFixtures.createTestFile(inputDir, "lib/helper.jar", "helper-content")
        TestFixtures.createTestFile(inputDir, "native/lib.dylib", "native-content")
        TestFixtures.createTestFile(inputDir, "config.xml", "config-content")

        val (privateKey, publicKey) = BundleManifestSigner.generateKeyPair()
        val signer = BundleManifestSigner.fromBase64(privateKey)
        val verifier = SignatureVerifier(publicKey)
        val builder = BundleManifestBuilder()
        val packager = BundlePackager()

        // Step 1: Collect files with platform constraints
        val bundleFiles = builder.collectFilesWithPlatformConstraints(inputDir.toFile())
        assertEquals(4, bundleFiles.size)

        // Step 2: Package bundles for each platform
        val platformBundles = packager.packageBundle(inputDir.toFile(), outputDir.toFile(), bundleFiles, targetPlatforms)
        assertEquals(1, platformBundles.size)
        assertTrue(platformBundles.containsKey("macos-arm64"))

        // Step 3: Build manifest
        val manifest = builder.build(
            inputDir = inputDir.toFile(),
            targetPlatforms = targetPlatforms,
            buildNumber = 12345,
            mainClass = "com.example.MainKt",
            minShellVersion = 1,
            platformBundles = platformBundles,
        )

        // Step 4: Sign manifest
        val signedManifest = signer.signManifest(manifest)
        assertTrue(signedManifest.signature.startsWith("ed25519:"))

        // Step 5: Verify output structure
        val bundleZipName = platformBundles["macos-arm64"]!!.zip
        assertTrue(Files.exists(outputDir.resolve(bundleZipName)), "Bundle zip should exist: $bundleZipName")
        assertTrue(Files.isDirectory(outputDir.resolve("files")))

        // Verify files/ contains hash-named files
        val filesDir = outputDir.resolve("files")
        for (bf in bundleFiles) {
            val hashName = bf.hash.removePrefix("sha256:")
            assertTrue(Files.exists(filesDir.resolve(hashName)), "Missing file: ${bf.path}")
        }

        // Verify signature is valid
        assertTrue(verifier.verifyManifest(signedManifest))
    }

    // ========== USE CASE 4: Shell Startup (Bundle Exists) ==========

    @Test
    fun shellStartup_validatesExistingBundle() = runBlocking {
        val appDataDir = createTempDir("integration-startup")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Create a JAR file
        val jarContent = "test-jar-content".toByteArray()
        val files = listOf(TestFixtures.createBundleFile("app.jar", jarContent))
        val manifest = TestFixtures.createSignedManifest(
            files = files,
            signer = keyPair.signer,
            buildNumber = 42,
            mainClass = "io.test.TestMain",
        )

        // Setup existing bundle on disk
        setupExistingBundle(appDataDir, manifest, mapOf("app.jar" to jarContent))

        val config = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = "https://updates.example.com", // Not used for validation
            publicKey = keyPair.publicKeyBase64,
            shellVersion = 1,
            platform = Platform.fromString("macos-arm64"),
            mainClass = "io.test.TestMain",
        )
        val bootstrap = BundleBootstrap(config)

        // Validate
        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(result)
        assertEquals(42, result.manifest.buildNumber)
        assertEquals(1, result.manifest.files.size)

        // Verify version path is correct
        assertEquals(appDataDir.resolve("versions/42"), result.versionPath)
    }

    @Test
    fun shellStartup_failsWithCorruptedCasFile() = runBlocking {
        val appDataDir = createTempDir("integration-startup-corrupt")
        val keyPair = TestFixtures.generateTestKeyPair()

        val originalContent = "original-content".toByteArray()
        val files = listOf(TestFixtures.createBundleFile("app.jar", originalContent))
        val manifest = TestFixtures.createSignedManifest(
            files = files,
            signer = keyPair.signer,
            buildNumber = 42,
        )

        // Setup bundle, then corrupt the CAS file (not version directory)
        setupExistingBundle(appDataDir, manifest, mapOf("app.jar" to originalContent))
        val casFilePath = appDataDir.resolve("cas/${files[0].hash.removePrefix("sha256:")}")
        Files.writeString(casFilePath, "corrupted!")

        val config = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = "https://updates.example.com",
            publicKey = keyPair.publicKeyBase64,
            shellVersion = 1,
            platform = Platform.fromString("macos-arm64"),
            mainClass = "io.test.TestMain",
        )
        val bootstrap = BundleBootstrap(config)

        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Failed>(result)
        assertTrue(result.reason.contains("verification failed"))
        assertEquals(1, result.failures.size)
        assertEquals("app.jar", result.failures[0].path)
        assertEquals("CAS file corrupted", result.failures[0].reason)
    }

    @Test
    fun shellStartup_requiresShellUpdate() = runBlocking {
        val appDataDir = createTempDir("integration-startup-shell-update")
        val keyPair = TestFixtures.generateTestKeyPair()

        val content = "content".toByteArray()
        val files = listOf(TestFixtures.createBundleFile("app.jar", content))
        val manifest = TestFixtures.createSignedManifest(
            files = files,
            signer = keyPair.signer,
            buildNumber = 42,
            minShellVersion = 5, // Requires shell v5
            shellUpdateUrl = "https://example.com/download",
        )

        setupExistingBundle(appDataDir, manifest, mapOf("app.jar" to content))

        val config = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = "https://updates.example.com",
            publicKey = keyPair.publicKeyBase64,
            shellVersion = 1, // Running shell v1
            platform = Platform.fromString("macos-arm64"),
            mainClass = "io.test.TestMain",
        )
        val bootstrap = BundleBootstrap(config)

        val result = bootstrap.validate()
        assertIs<BundleValidationResult.ShellUpdateRequired>(result)
        assertEquals(1, result.currentVersion)
        assertEquals(5, result.requiredVersion)
        assertEquals("https://example.com/download", result.updateUrl)
    }

    // ========== HELPER FUNCTIONS ==========

    /**
     * Setup an existing bundle on disk for testing.
     */
    private fun setupExistingBundle(
        appDataDir: Path,
        manifest: BundleManifest,
        fileContents: Map<String, ByteArray>,
    ) {
        // Create directory structure
        val versionsDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(versionsDir)
        Files.createDirectories(casDir)

        // Write files to version directory
        for (file in manifest.files) {
            val content = fileContents[file.path]
                ?: throw IllegalArgumentException("Missing content for ${file.path}")
            val filePath = versionsDir.resolve(file.path)
            Files.createDirectories(filePath.parent)
            Files.write(filePath, content)

            // Also write to CAS (without sha256: prefix)
            val casPath = casDir.resolve(file.hash.removePrefix("sha256:"))
            Files.write(casPath, content)
        }

        // Write manifest.json
        Files.writeString(appDataDir.resolve("manifest.json"), json.encodeToString(manifest))
    }

    /**
     * Create a bundle.zip in memory for MockWebServer.
     */
    private fun createBundleZip(files: List<BundleFile>, contents: Map<String, ByteArray>): Buffer {
        val buffer = Buffer()
        ZipOutputStream(buffer.outputStream()).use { zip ->
            for (file in files) {
                val content = contents[file.path]
                    ?: throw IllegalArgumentException("Missing content for ${file.path}")
                zip.putNextEntry(ZipEntry(file.path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return buffer
    }

    // ========== EDGE CASE TESTS ==========

    @Test
    @Timeout(60)
    fun shellFirstRun_incrementalDownload() = runBlocking {
        // Test incremental download: some files already in CAS, only missing files downloaded
        val appDataDir = createTempDir("integration-incremental")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Create two files - we'll pre-seed one in CAS
        // Make existing file large (100KB) so incremental is favored over full bundle
        // Incremental cost = 10KB + 50KB overhead = 60KB
        // Full bundle cost = 110KB
        // Incremental wins!
        val existingContent = "A".repeat(100_000).toByteArray() // 100KB
        val newContent = "B".repeat(10_000).toByteArray() // 10KB

        val existingFile = TestFixtures.createBundleFile("existing.jar", existingContent)
        val newFile = TestFixtures.createBundleFile("new.jar", newContent)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(existingFile, newFile),
            signer = keyPair.signer,
            buildNumber = 100,
        )

        // Pre-seed the CAS with the existing file
        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val existingHash = existingFile.hash.removePrefix("sha256:")
        Files.write(casDir.resolve(existingHash), existingContent)

        // Server returns only the missing file (incremental download)
        mockServer.enqueue(
            MockResponse()
                .setBody(Buffer().write(newContent))
                .setHeader("Content-Type", "application/octet-stream")
        )

        // Use DownloadManager directly
        val storageManager = io.runwork.bundle.updater.storage.StorageManager(appDataDir)
        val platform = Platform.fromString("macos-arm64")
        val downloadManager = io.runwork.bundle.updater.download.DownloadManager(
            mockServer.url("/").toString().trimEnd('/'),
            storageManager,
            platform
        )

        val progressUpdates = mutableListOf<DownloadProgress>()
        val downloadResult = downloadManager.downloadBundle(manifest) { progressUpdates.add(it) }

        assertIs<DownloadResult.Success>(downloadResult)

        // Verify the server only received 1 request (just the missing file, not manifest or existing file)
        assertEquals(1, mockServer.requestCount)

        // Verify the request was for the missing file
        val request = mockServer.takeRequest()
        val newHash = newFile.hash.removePrefix("sha256:")
        assertEquals("/files/$newHash", request.path)

        // Finalize: prepare version
        storageManager.prepareVersion(manifest, platform)

        // Verify both files are in version directory
        assertTrue(Files.exists(appDataDir.resolve("versions/100/existing.jar")))
        assertTrue(Files.exists(appDataDir.resolve("versions/100/new.jar")))

        // Verify content
        assertEquals(
            String(existingContent),
            Files.readString(appDataDir.resolve("versions/100/existing.jar"))
        )
        assertEquals(
            String(newContent),
            Files.readString(appDataDir.resolve("versions/100/new.jar"))
        )

        downloadManager.close()
    }

    // ========== FILE:// URL INTEGRATION TESTS ==========
    // These tests exercise BundleUpdater.downloadLatest() with file:// URLs
    // to prevent regressions like the nested withContext hang bug.

    @Test
    @Timeout(60)
    fun fileUrl_downloadLatest_fullBundle() = runBlocking {
        // Test full bundle download with file:// URL via BundleUpdater.downloadLatest()
        // Uses small files to force full bundle ZIP strategy
        val appDataDir = createTempDir("integration-fileurl-full")
        val fileBundleDir = createTempDir("file-bundle-server-full")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Create small files (< 10KB each) so full bundle is chosen over incremental
        // Full bundle cost = total size (small)
        // Incremental cost = missing size + numFiles * 50KB overhead (larger)
        val file1Content = "content-file-1".toByteArray()
        val file2Content = "content-file-2".toByteArray()
        val file1 = TestFixtures.createBundleFile("lib/app.jar", file1Content)
        val file2 = TestFixtures.createBundleFile("config.xml", file2Content)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(file1, file2),
            signer = keyPair.signer,
            buildNumber = 100,
        )

        // Create file-based bundle server with ZIP
        val files = mapOf(
            file1.hash to file1Content,
            file2.hash to file2Content,
        )
        val baseUrl = TestFixtures.createFileBundleServer(fileBundleDir, manifest, files, includeZip = true)

        val updater = createFileUrlBundleUpdater(appDataDir, baseUrl, keyPair.publicKeyBase64)

        // Download using BundleUpdater.downloadLatest() - this was the hang point
        val result = updater.downloadLatest()

        assertIs<DownloadResult.Success>(result)

        // Verify files end up in version directory
        assertTrue(Files.exists(appDataDir.resolve("versions/100/lib/app.jar")))
        assertTrue(Files.exists(appDataDir.resolve("versions/100/config.xml")))

        // Verify manifest is saved
        assertTrue(Files.exists(appDataDir.resolve("manifest.json")))

        updater.close()
    }

    @Test
    @Timeout(60)
    fun fileUrl_downloadLatest_incremental() = runBlocking {
        // Test incremental download with file:// URL via BundleUpdater.downloadLatest()
        // Pre-seeds CAS with large existing files, only new file should be downloaded
        val appDataDir = createTempDir("integration-fileurl-incremental")
        val fileBundleDir = createTempDir("file-bundle-server-incremental")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Create files: large existing file (already in CAS) + small new file
        // Incremental cost = 10KB + 50KB overhead = 60KB
        // Full bundle cost = 110KB
        // Incremental wins!
        val existingContent = "A".repeat(100_000).toByteArray() // 100KB
        val newContent = "B".repeat(10_000).toByteArray() // 10KB

        val existingFile = TestFixtures.createBundleFile("existing.jar", existingContent)
        val newFile = TestFixtures.createBundleFile("new.jar", newContent)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(existingFile, newFile),
            signer = keyPair.signer,
            buildNumber = 100,
        )

        // Pre-seed the CAS with the existing file
        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val existingHash = existingFile.hash.removePrefix("sha256:")
        Files.write(casDir.resolve(existingHash), existingContent)

        // Create file-based bundle server with only the new file
        // (server only needs files that will be downloaded)
        val files = mapOf(
            existingFile.hash to existingContent,
            newFile.hash to newContent,
        )
        val baseUrl = TestFixtures.createFileBundleServer(fileBundleDir, manifest, files, includeZip = false)

        val updater = createFileUrlBundleUpdater(appDataDir, baseUrl, keyPair.publicKeyBase64)

        // Download using BundleUpdater.downloadLatest()
        val result = updater.downloadLatest()

        assertIs<DownloadResult.Success>(result)

        // Verify both files end up in version directory
        assertTrue(Files.exists(appDataDir.resolve("versions/100/existing.jar")))
        assertTrue(Files.exists(appDataDir.resolve("versions/100/new.jar")))

        // Verify content
        assertEquals(
            String(existingContent),
            Files.readString(appDataDir.resolve("versions/100/existing.jar"))
        )
        assertEquals(
            String(newContent),
            Files.readString(appDataDir.resolve("versions/100/new.jar"))
        )

        updater.close()
    }

    @Test
    @Timeout(60)
    fun fileUrl_downloadLatest_progressReporting() = runBlocking {
        // Test that progress callbacks work correctly with file:// URLs via BundleUpdater.downloadLatest()
        // This was the specific failure mode in the hang bug
        val appDataDir = createTempDir("integration-fileurl-progress")
        val fileBundleDir = createTempDir("file-bundle-server-progress")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Create a medium-sized file to generate progress events
        val content = "X".repeat(50_000).toByteArray() // 50KB
        val file = TestFixtures.createBundleFile("data.bin", content)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(file),
            signer = keyPair.signer,
            buildNumber = 100,
        )

        val files = mapOf(file.hash to content)
        val baseUrl = TestFixtures.createFileBundleServer(fileBundleDir, manifest, files, includeZip = true)

        val updater = createFileUrlBundleUpdater(appDataDir, baseUrl, keyPair.publicKeyBase64)

        // Collect progress events
        val progressEvents = mutableListOf<DownloadProgress>()
        val result = updater.downloadLatest { progress ->
            progressEvents.add(progress)
        }

        assertIs<DownloadResult.Success>(result)

        // Verify progress events were received (main test for the hang bug fix)
        assertTrue(progressEvents.isNotEmpty(), "Should receive progress events")

        // Verify progress is monotonically increasing
        var lastBytes = 0L
        for (progress in progressEvents) {
            assertTrue(progress.bytesDownloaded >= lastBytes, "Progress should be monotonically increasing")
            lastBytes = progress.bytesDownloaded
        }

        // Verify final progress matches expected
        val lastProgress = progressEvents.last()
        assertTrue(lastProgress.bytesDownloaded > 0, "Should have downloaded some bytes")

        updater.close()
    }

    @Test
    @Timeout(60)
    fun fileUrl_downloadLatest_alreadyUpToDate() = runBlocking {
        // Test that same-version returns AlreadyUpToDate (via existing manifest.json on disk)
        val appDataDir = createTempDir("integration-fileurl-uptodate")
        val fileBundleDir = createTempDir("file-bundle-server-uptodate")
        val keyPair = TestFixtures.generateTestKeyPair()

        val content = "content".toByteArray()
        val file = TestFixtures.createBundleFile("app.jar", content)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(file),
            signer = keyPair.signer,
            buildNumber = 100,
        )

        // Setup existing bundle on disk so getCurrentBuildNumber() returns 100
        setupExistingBundle(appDataDir, manifest, mapOf("app.jar" to content))

        val files = mapOf(file.hash to content)
        val baseUrl = TestFixtures.createFileBundleServer(fileBundleDir, manifest, files, includeZip = true)

        // Create updater (currentBuildNumber config is not used by downloadLatest - it reads from disk)
        val updater = createFileUrlBundleUpdater(
            appDataDir,
            baseUrl,
            keyPair.publicKeyBase64,
        )

        val result = updater.downloadLatest()

        assertIs<DownloadResult.AlreadyUpToDate>(result)

        updater.close()
    }

    @Test
    @Timeout(60)
    fun fileUrl_downloadLatest_signatureVerificationFails() = runBlocking {
        // Test that invalid signature is rejected with file:// URLs
        val appDataDir = createTempDir("integration-fileurl-signature-fail")
        val fileBundleDir = createTempDir("file-bundle-server-signature-fail")

        // Use two different key pairs
        val serverKeyPair = TestFixtures.generateTestKeyPair()
        val clientKeyPair = TestFixtures.generateTestKeyPair()

        val content = "content".toByteArray()
        val file = TestFixtures.createBundleFile("app.jar", content)

        // Sign with server's key
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(file),
            signer = serverKeyPair.signer,
            buildNumber = 100,
        )

        val files = mapOf(file.hash to content)
        val baseUrl = TestFixtures.createFileBundleServer(fileBundleDir, manifest, files, includeZip = true)

        // Configure updater with client's public key (different from server's)
        val updater = createFileUrlBundleUpdater(appDataDir, baseUrl, clientKeyPair.publicKeyBase64)

        val result = updater.downloadLatest()

        // Should fail signature verification
        assertIs<DownloadResult.Failure>(result)
        assertTrue(result.error.contains("signature", ignoreCase = true))

        updater.close()
    }

    /**
     * Helper to create a BundleUpdater configured for file:// URLs.
     */
    private fun createFileUrlBundleUpdater(
        appDataDir: Path,
        baseUrl: String,
        publicKey: String,
        currentBuildNumber: Long = 0,
    ): BundleUpdater {
        val config = BundleUpdaterConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = baseUrl,
            publicKey = publicKey,
            currentBuildNumber = currentBuildNumber,
            platform = Platform.fromString("macos-arm64"),
        )
        return BundleUpdater(config)
    }

    // ========== EXISTING DOWNGRADE TEST ==========

    @Test
    fun bundleSelfUpdate_preventsDowngrade() = runBlocking {
        // Test that attempting to "update" to an older version is prevented
        val appDataDir = createTempDir("integration-downgrade")
        val keyPair = TestFixtures.generateTestKeyPair()

        // Setup existing bundle at version 200
        val content = "v2-content".toByteArray()
        val files = listOf(TestFixtures.createBundleFile("app.jar", content))
        val currentManifest = TestFixtures.createSignedManifest(
            files = files,
            signer = keyPair.signer,
            buildNumber = 200,
        )
        setupExistingBundle(appDataDir, currentManifest, mapOf("app.jar" to content))

        // Server returns an OLDER manifest (build 100) - simulates downgrade attack
        val olderManifest = TestFixtures.createSignedManifest(
            files = files,
            signer = keyPair.signer,
            buildNumber = 100, // Older than current 200!
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(olderManifest))
                .setHeader("Content-Type", "application/json")
        )

        val updaterConfig = BundleUpdaterConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
            publicKey = keyPair.publicKeyBase64,
            currentBuildNumber = 200, // Currently at version 200
            platform = Platform.fromString("macos-arm64"),
        )
        val updater = BundleUpdater(updaterConfig)

        // Attempt to download - should be prevented
        val downloadResult = updater.downloadLatest()

        // Should return AlreadyUpToDate (downgrade prevented)
        assertIs<DownloadResult.AlreadyUpToDate>(downloadResult)

        // Verify version 100 was NOT created
        assertTrue(!Files.exists(appDataDir.resolve("versions/100")))

        // Verify version 200 still exists
        assertTrue(Files.exists(appDataDir.resolve("versions/200")))

        updater.close()
    }
}
