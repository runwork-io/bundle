package io.runwork.bundle.updater

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.updater.result.DownloadResult
import io.runwork.bundle.updater.result.UpdateCheckResult
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleUpdaterTest {

    private lateinit var tempDir: Path
    private lateinit var appDataDir: Path
    private lateinit var mockServer: MockWebServer
    private lateinit var keyPair: TestFixtures.TestKeyPair

    private val json = Json { prettyPrint = true }

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("bundle-updater-test")
        appDataDir = tempDir.resolve("app-data")
        Files.createDirectories(appDataDir)
        mockServer = MockWebServer()
        mockServer.start()
        keyPair = TestFixtures.generateTestKeyPair()
    }

    @AfterTest
    fun tearDown() {
        mockServer.shutdown()
        TestFixtures.deleteRecursively(tempDir)
    }

    // ========== checkNow() tests ==========

    @Test
    fun checkNow_returnsUpdateAvailable() = runBlocking {
        // Setup: manifest has buildNumber 200, current is 100
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 200
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.checkNow()

        assertIs<UpdateCheckResult.UpdateAvailable>(result)
        assertEquals(200, result.info.newBuildNumber)
        assertEquals(100, result.info.currentBuildNumber)

        updater.close()
    }

    @Test
    fun checkNow_returnsUpToDateWhenCurrent() = runBlocking {
        // Setup: manifest has same buildNumber as current
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 100
        )

        // First request for checkNow
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )
        // Second request for tryCleanupIfUpToDate (called internally when up-to-date)
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.checkNow()

        assertIs<UpdateCheckResult.UpToDate>(result)

        updater.close()
    }

    @Test
    fun checkNow_returnsFailedOnNetworkError() = runBlocking {
        // Setup: mock server returns error
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.checkNow()

        assertIs<UpdateCheckResult.Failed>(result)
        assertTrue(result.error.isNotEmpty())

        updater.close()
    }

    @Test
    fun checkNow_returnsFailedOnSignatureFailure() = runBlocking {
        // Setup: manifest with invalid signature
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createTestManifest(
            files = listOf(bundleFile),
            buildNumber = 200
        ).copy(signature = "ed25519:invalid_base64_signature!")

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.checkNow()

        assertIs<UpdateCheckResult.Failed>(result)
        assertTrue(result.error.contains("signature", ignoreCase = true))

        updater.close()
    }

    @Test
    fun checkNow_preventsDowngrade() = runBlocking {
        // Setup: manifest has OLDER buildNumber than current
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 50 // Older than current 100
        )

        // First request for checkNow
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )
        // Second request for tryCleanupIfUpToDate (called internally when up-to-date)
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.checkNow()

        // Should return UpToDate, NOT UpdateAvailable (downgrade prevention)
        assertIs<UpdateCheckResult.UpToDate>(result)

        updater.close()
    }

    // ========== downloadLatest() tests ==========

    @Test
    fun downloadLatest_preventsDowngrade() = runBlocking {
        // Setup existing bundle at build 100
        val initialContent = "v1-content".toByteArray()
        val initialFile = TestFixtures.createBundleFile("app.jar", initialContent)
        val initialManifest = TestFixtures.createSignedManifest(
            files = listOf(initialFile),
            signer = keyPair.signer,
            buildNumber = 100
        )
        setupExistingBundle(initialManifest, mapOf("app.jar" to initialContent))

        // Server returns older manifest
        val olderManifest = TestFixtures.createSignedManifest(
            files = listOf(initialFile),
            signer = keyPair.signer,
            buildNumber = 50 // Older build
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(olderManifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.downloadLatest()

        assertIs<DownloadResult.AlreadyUpToDate>(result)

        updater.close()
    }

    @Test
    fun downloadLatest_returnsFailureOnSignatureError() = runBlocking {
        // Setup: manifest with invalid signature
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createTestManifest(
            files = listOf(bundleFile),
            buildNumber = 200
        ).copy(signature = "ed25519:invalid!")

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.downloadLatest()

        assertIs<DownloadResult.Failure>(result)
        assertTrue(result.error.contains("signature", ignoreCase = true))

        updater.close()
    }

    // ========== cleanup() tests ==========

    @Test
    fun cleanup_returnsNullWhenUpdateAvailable() = runBlocking {
        // Setup: current is 100, server has 200
        val content = "content".toByteArray()
        val file = TestFixtures.createBundleFile("app.jar", content)

        // Setup existing bundle at 100
        val currentManifest = TestFixtures.createSignedManifest(
            files = listOf(file),
            signer = keyPair.signer,
            buildNumber = 100
        )
        setupExistingBundle(currentManifest, mapOf("app.jar" to content))

        // Server returns newer manifest
        val newManifest = TestFixtures.createSignedManifest(
            files = listOf(file),
            signer = keyPair.signer,
            buildNumber = 200
        )
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(newManifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.cleanup()

        // Should NOT cleanup when update is available
        assertNull(result)

        updater.close()
    }

    @Test
    fun cleanup_runsWhenUpToDate() = runBlocking {
        // Setup: current is 100, server also has 100 (up to date)
        val content = "content".toByteArray()
        val file = TestFixtures.createBundleFile("app.jar", content)

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(file),
            signer = keyPair.signer,
            buildNumber = 100
        )
        setupExistingBundle(manifest, mapOf("app.jar" to content))

        // Also create an old version directory to be cleaned up
        val oldVersionDir = appDataDir.resolve("versions/50")
        Files.createDirectories(oldVersionDir)
        Files.writeString(oldVersionDir.resolve("old.txt"), "old content")

        // Server returns same version (up to date)
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100)
        val result = updater.cleanup()

        // Should cleanup since we're up to date
        assertTrue(result != null)
        assertTrue(result.versionsRemoved.contains(50L))

        // Verify old version was removed
        assertTrue(!Files.exists(oldVersionDir))

        updater.close()
    }

    // ========== getState() tests ==========

    @Test
    fun getState_reflectsCheckingState() = runBlocking {
        // Initially should be Idle
        val updater = createUpdater(currentBuildNumber = 100)
        assertIs<BundleUpdaterState.Idle>(updater.getState())

        // Queue responses - first for checkNow, second for tryCleanupIfUpToDate
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 100
        )
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        // After checkNow completes, should be back to Idle (since up-to-date)
        updater.checkNow()
        assertIs<BundleUpdaterState.Idle>(updater.getState())

        updater.close()
    }

    // ========== Helper methods ==========

    private fun createUpdater(currentBuildNumber: Long): BundleUpdater {
        val config = BundleUpdaterConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
            publicKey = keyPair.publicKeyBase64,
            currentBuildNumber = currentBuildNumber,
            platform = Platform.fromString("macos-arm64")
        )
        return BundleUpdater(config)
    }

    private fun setupExistingBundle(
        manifest: BundleManifest,
        fileContents: Map<String, ByteArray>
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
}
