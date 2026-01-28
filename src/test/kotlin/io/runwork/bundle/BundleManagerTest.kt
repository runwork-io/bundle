package io.runwork.bundle

import io.runwork.bundle.download.DownloadProgress
import io.runwork.bundle.download.DownloadResult
import io.runwork.bundle.manifest.BundleFile
import io.runwork.bundle.manifest.FileType
import io.runwork.bundle.storage.StorageManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class BundleManagerTest {

    private lateinit var tempDir: Path
    private lateinit var mockServer: MockWebServer
    private lateinit var bundleManager: BundleManager
    private lateinit var signer: io.runwork.bundle.manifest.ManifestSigner
    private lateinit var publicKey: String

    private val json = Json { prettyPrint = true }

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("bundle-manager-test")
        mockServer = MockWebServer()
        mockServer.start()

        // Generate key pair
        val (privateKey, pubKey) = io.runwork.bundle.manifest.ManifestSigner.generateKeyPair()
        publicKey = pubKey
        signer = io.runwork.bundle.manifest.ManifestSigner.fromBase64(privateKey)

        bundleManager = BundleManager(
            BundleConfig(
                baseUrl = mockServer.url("/").toString().trimEnd('/'),
                publicKey = publicKey,
                platform = "macos-arm64",
                appDataDir = tempDir
            )
        )
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun checkForUpdate_returnsUpToDateWhenCurrentVersion() = runTest {
        // Install current version
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        setupInstalledVersion(42, listOf("file.txt" to content))

        // Server has same version
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            signer = signer,
            buildNumber = 42,
            platform = "macos-arm64"
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val result = bundleManager.checkForUpdate()

        assertIs<UpdateCheckResult.UpToDate>(result)
        assertEquals(42, result.buildNumber)
    }

    @Test
    fun checkForUpdate_returnsUpdateAvailableWhenNewer() = runTest {
        // Install version 1
        setupInstalledVersion(1, listOf("file.txt" to "v1 content"))

        // Server has version 2
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(
                    path = "file.txt",
                    hash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    size = 1000,
                    type = FileType.RESOURCE
                )
            ),
            signer = signer,
            buildNumber = 2,
            platform = "macos-arm64"
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val result = bundleManager.checkForUpdate()

        assertIs<UpdateCheckResult.UpdateAvailable>(result)
        assertEquals(2, result.info.buildNumber)
        assertEquals(1, result.info.currentBuildNumber)
    }

    @Test
    fun checkForUpdate_returnsSignatureInvalidForBadSignature() = runTest {
        // Create manifest with wrong signature
        val manifest = TestFixtures.createTestManifest(
            files = emptyList(),
            buildNumber = 100,
            platform = "macos-arm64"
        ).copy(signature = "ed25519:invalidSignature==")

        // Need to enqueue multiple responses for retries
        repeat(3) {
            mockServer.enqueue(
                MockResponse()
                    .setBody(json.encodeToString(manifest))
                    .setHeader("Content-Type", "application/json")
            )
        }

        val result = bundleManager.checkForUpdate()

        assertIs<UpdateCheckResult.SignatureInvalid>(result)
    }

    @Test
    fun checkForUpdate_returnsPlatformMismatchForWrongPlatform() = runTest {
        val manifest = TestFixtures.createSignedManifest(
            files = emptyList(),
            signer = signer,
            buildNumber = 100,
            platform = "windows-x86_64" // Different platform
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val result = bundleManager.checkForUpdate()

        assertIs<UpdateCheckResult.PlatformMismatch>(result)
        assertEquals("macos-arm64", result.expected)
        assertEquals("windows-x86_64", result.actual)
    }

    @Test
    fun checkForUpdate_returnsNetworkErrorOnConnectionFailure() = runTest {
        mockServer.shutdown()

        val result = bundleManager.checkForUpdate()

        assertIs<UpdateCheckResult.NetworkError>(result)
    }

    @Test
    fun downloadUpdate_downloadsAndPrepares() = runTest {
        val content = "New content"
        val hash = TestFixtures.computeHash(content.toByteArray())

        // Pre-store file in CAS so no download needed
        val storageManager = StorageManager(tempDir)
        val tempFile = TestFixtures.createTestFile(tempDir, "temp-prep.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            signer = signer,
            buildNumber = 42,
            platform = "macos-arm64"
        )

        val updateInfo = UpdateInfo(
            buildNumber = 42,
            currentBuildNumber = 0,
            manifest = manifest
        )

        val progressUpdates = mutableListOf<DownloadProgress>()
        val result = bundleManager.downloadUpdate(updateInfo) { progress ->
            progressUpdates.add(progress)
        }

        assertIs<DownloadResult.Success>(result)
        assertEquals(42, result.buildNumber)

        // Verify version is prepared
        assertTrue(Files.exists(tempDir.resolve("versions/42/.complete")))
        assertTrue(Files.exists(tempDir.resolve("versions/42/file.txt")))

        // Verify current version is set
        assertEquals(42, bundleManager.getCurrentBuildNumber())
    }

    @Test
    fun downloadUpdate_setsCurrentVersion() = runTest {
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())

        // Pre-store file in CAS
        val storageManager = StorageManager(tempDir)
        val tempFile = TestFixtures.createTestFile(tempDir, "temp-prep.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            signer = signer,
            buildNumber = 100,
            platform = "macos-arm64"
        )

        val updateInfo = UpdateInfo(buildNumber = 100, currentBuildNumber = 0, manifest = manifest)
        bundleManager.downloadUpdate(updateInfo) {}

        assertEquals(100, bundleManager.getCurrentBuildNumber())
    }

    @Test
    fun verifyBundle_returnsEmptyForValid() = runTest {
        val content = "Valid content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        setupInstalledVersion(42, listOf("file.txt" to content))

        val failures = bundleManager.verifyBundle()

        assertTrue(failures.isEmpty())
    }

    @Test
    fun verifyBundle_returnsFailuresForTampered() = runTest {
        val content = "Original content"
        setupInstalledVersion(42, listOf("file.txt" to content))

        // Tamper with file
        Files.writeString(tempDir.resolve("versions/42/file.txt"), "Tampered content")

        val failures = bundleManager.verifyBundle()

        assertEquals(1, failures.size)
        assertEquals("file.txt", failures[0].path)
    }

    @Test
    fun hasBundleInstalled_returnsFalseInitially() {
        assertFalse(bundleManager.hasBundleInstalled())
    }

    @Test
    fun hasBundleInstalled_returnsTrueAfterDownload() = runTest {
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())

        // Pre-store file in CAS
        val storageManager = StorageManager(tempDir)
        val tempFile = TestFixtures.createTestFile(tempDir, "temp-prep.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            signer = signer,
            buildNumber = 42,
            platform = "macos-arm64"
        )

        val updateInfo = UpdateInfo(buildNumber = 42, currentBuildNumber = 0, manifest = manifest)
        bundleManager.downloadUpdate(updateInfo) {}

        assertTrue(bundleManager.hasBundleInstalled())
    }

    @Test
    fun getCurrentBuildNumber_returnsNullWhenNotInstalled() {
        assertNull(bundleManager.getCurrentBuildNumber())
    }

    @Test
    fun getCurrentManifest_returnsNullWhenNotInstalled() {
        assertNull(bundleManager.getCurrentManifest())
    }

    @Test
    fun getCurrentManifest_returnsManifestAfterDownload() = runTest {
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())

        // Pre-store file in CAS
        val storageManager = StorageManager(tempDir)
        val tempFile = TestFixtures.createTestFile(tempDir, "temp-prep.txt", content)
        storageManager.contentStore.store(tempFile)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(path = "file.txt", hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
            ),
            signer = signer,
            buildNumber = 42,
            platform = "macos-arm64"
        )

        val updateInfo = UpdateInfo(buildNumber = 42, currentBuildNumber = 0, manifest = manifest)
        bundleManager.downloadUpdate(updateInfo) {}

        val currentManifest = bundleManager.getCurrentManifest()

        assertNotNull(currentManifest)
        assertEquals(42, currentManifest.buildNumber)
    }

    @Test
    fun repairBundle_relinkFilesWhenRepaired() = runTest {
        val content = "Content"
        val hash = TestFixtures.computeHash(content.toByteArray())
        setupInstalledVersion(42, listOf("file.txt" to content))

        // Delete the hard link in version directory (simulates corruption)
        Files.delete(tempDir.resolve("versions/42/file.txt"))

        val failures = bundleManager.verifyBundle()
        assertEquals(1, failures.size)

        // File is still in CAS, so no download needed - just re-link
        // The repair should re-link from CAS
        val result = bundleManager.repairBundle(failures) {}

        assertIs<RepairResult.Success>(result)

        // Verify file is now present and correct
        assertTrue(Files.exists(tempDir.resolve("versions/42/file.txt")))
        val repairedContent = Files.readString(tempDir.resolve("versions/42/file.txt"))
        assertEquals(content, repairedContent)
    }

    @Test
    fun cleanup_cleansUpTempFiles() = runTest {
        // Create some temp files
        val storageManager = StorageManager(tempDir)
        val temp1 = storageManager.createTempFile("test")
        val temp2 = storageManager.createTempFile("test")

        assertTrue(Files.exists(temp1))
        assertTrue(Files.exists(temp2))

        bundleManager.cleanup()

        assertFalse(Files.exists(temp1))
        assertFalse(Files.exists(temp2))
    }

    private fun setupInstalledVersion(buildNumber: Long, files: List<Pair<String, String>>) {
        val storageManager = StorageManager(tempDir)

        val bundleFiles = files.map { (path, content) ->
            val hash = TestFixtures.computeHash(content.toByteArray())
            val tempFile = TestFixtures.createTestFile(tempDir, "temp-${path.replace("/", "-")}", content)
            storageManager.contentStore.store(tempFile)
            BundleFile(path = path, hash = hash, size = content.length.toLong(), type = FileType.RESOURCE)
        }

        val manifest = TestFixtures.createSignedManifest(
            files = bundleFiles,
            signer = signer,
            buildNumber = buildNumber,
            platform = "macos-arm64"
        )

        storageManager.prepareVersion(manifest)
        storageManager.saveManifest(json.encodeToString(manifest))
        storageManager.setCurrentVersion(buildNumber)
    }
}
