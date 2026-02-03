package io.runwork.bundle.updater

import io.runwork.bundle.common.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BundleUpdaterRetryTest {

    private lateinit var tempDir: Path
    private lateinit var appDataDir: Path
    private lateinit var mockServer: MockWebServer
    private lateinit var keyPair: TestFixtures.TestKeyPair

    private val json = Json { prettyPrint = true }
    private val fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneId.of("UTC"))

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("bundle-retry-test")
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

    // ========== downloadLatest() retry tests ==========

    @Test
    @Timeout(10)
    fun downloadLatest_simpleSuccess() = runBlocking {
        // Simple test: manifest succeeds on first try
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 200
        )

        // Manifest request succeeds immediately
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )
        // For full bundle download: bundle.zip
        mockServer.enqueue(
            MockResponse()
                .setBody(createBundleZip(listOf(bundleFile), mapOf("app.jar" to fileContent)))
                .setHeader("Content-Type", "application/zip")
        )

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 0) // No retries
        val events = mutableListOf<BundleUpdateEvent>()
        withContext(Dispatchers.IO) {
            updater.downloadLatest().collect { events.add(it) }
        }
        updater.close()

        assertTrue(events.isNotEmpty(), "Expected at least one event")

        val updateReady = events.filterIsInstance<BundleUpdateEvent.UpdateReady>()
        assertEquals(1, updateReady.size, "Expected UpdateReady event")
    }

    @Test
    @Timeout(30)
    fun downloadLatest_retries500_thenSucceeds() = runBlocking {
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 200
        )

        // First two requests fail with 500, third succeeds
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )
        // Enqueue bundle.zip download
        mockServer.enqueue(
            MockResponse()
                .setBody(createBundleZip(listOf(bundleFile), mapOf("app.jar" to fileContent)))
                .setHeader("Content-Type", "application/zip")
        )

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        // Should have: Checking, BackingOff, BackingOff, UpdateAvailable, Downloading..., UpdateReady
        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertEquals(2, backoffEvents.size, "Expected 2 backoff events, got ${backoffEvents.size}")
        assertEquals(1, backoffEvents[0].retryNumber)
        assertEquals(2, backoffEvents[1].retryNumber)

        val updateReady = events.filterIsInstance<BundleUpdateEvent.UpdateReady>()
        assertEquals(1, updateReady.size, "Expected UpdateReady event")
        assertEquals(200, updateReady[0].newBuildNumber)
    }

    @Test
    fun downloadLatest_exhaustsRetries_emitsError() = runBlocking {
        // All requests fail with 500
        repeat(5) {
            mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        }

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        // Should have: Checking, BackingOff x3, Error
        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertEquals(3, backoffEvents.size, "Expected 3 backoff events")

        val errorEvents = events.filterIsInstance<BundleUpdateEvent.Error>()
        assertEquals(1, errorEvents.size, "Expected Error event after retries exhausted")
    }

    @Test
    fun downloadLatest_signatureFailure_noRetry() = runBlocking {
        // Return manifest with invalid signature
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createTestManifest(
            files = listOf(bundleFile),
            buildNumber = 200
        ).copy(signature = "ed25519:invalid_signature!")

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        // Should have: Checking, Error (no backoff - signature failures aren't retried)
        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertTrue(backoffEvents.isEmpty(), "Signature failures should not trigger backoff")

        val errorEvents = events.filterIsInstance<BundleUpdateEvent.Error>()
        assertEquals(1, errorEvents.size, "Expected Error event")
        assertFalse(errorEvents[0].error.isRecoverable, "Signature failure should not be recoverable")
        assertTrue(
            errorEvents[0].error.message.contains("signature", ignoreCase = true),
            "Error message should mention signature"
        )
    }

    @Test
    fun downloadLatest_platformMismatch_noRetry() = runBlocking {
        // Return manifest for different platform
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 200,
            platforms = listOf("windows-x64") // Not macos-arm64
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        // Should have: Checking, Error (no backoff - platform mismatches aren't retried)
        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertTrue(backoffEvents.isEmpty(), "Platform mismatches should not trigger backoff")

        val errorEvents = events.filterIsInstance<BundleUpdateEvent.Error>()
        assertEquals(1, errorEvents.size, "Expected Error event")
        assertFalse(errorEvents[0].error.isRecoverable, "Platform mismatch should not be recoverable")
    }

    @Test
    fun downloadLatest_backoffEventsContainCorrectDelays() = runBlocking {
        // All requests fail with 500
        repeat(5) {
            mockServer.enqueue(MockResponse().setResponseCode(500))
        }

        val updater = createUpdater(
            currentBuildNumber = 100,
            maxAttempts = 3,
            initialDelay = 2.seconds,
            multiplier = 3.0
        )
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertEquals(3, backoffEvents.size)
        // Delays should be: 2s, 6s, 18s
        assertEquals(2L, backoffEvents[0].delaySeconds)
        assertEquals(6L, backoffEvents[1].delaySeconds)
        assertEquals(18L, backoffEvents[2].delaySeconds)
    }

    @Test
    fun downloadLatest_http429_isRetried() = runBlocking {
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 200
        )

        // First request returns 429, second succeeds
        mockServer.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )
        mockServer.enqueue(
            MockResponse()
                .setBody(createBundleZip(listOf(bundleFile), mapOf("app.jar" to fileContent)))
                .setHeader("Content-Type", "application/zip")
        )

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertEquals(1, backoffEvents.size, "HTTP 429 should trigger retry")

        val updateReady = events.filterIsInstance<BundleUpdateEvent.UpdateReady>()
        assertEquals(1, updateReady.size)
    }

    @Test
    fun downloadLatest_http404_notRetried() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        // HTTP 404 is a client error - should not retry
        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertTrue(backoffEvents.isEmpty(), "HTTP 404 should not trigger retry")

        val errorEvents = events.filterIsInstance<BundleUpdateEvent.Error>()
        assertEquals(1, errorEvents.size)
    }

    @Test
    fun downloadLatest_downloadFailure_isRetried() = runBlocking {
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 200
        )

        // Manifest succeeds
        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )
        // Bundle.zip download fails twice with 500, then succeeds
        mockServer.enqueue(MockResponse().setResponseCode(500))
        mockServer.enqueue(MockResponse().setResponseCode(500))
        mockServer.enqueue(
            MockResponse()
                .setBody(createBundleZip(listOf(bundleFile), mapOf("app.jar" to fileContent)))
                .setHeader("Content-Type", "application/zip")
        )

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertEquals(2, backoffEvents.size, "Download failures should trigger retries")

        val updateReady = events.filterIsInstance<BundleUpdateEvent.UpdateReady>()
        assertEquals(1, updateReady.size)
    }

    @Test
    fun downloadLatest_stateUpdatedDuringBackoff() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(500))
        mockServer.enqueue(MockResponse().setResponseCode(500))

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 1)
        var backoffStateObserved = false

        withContext(Dispatchers.IO) {
            updater.downloadLatest().collect { event ->
                if (event is BundleUpdateEvent.BackingOff) {
                    val state = updater.getState()
                    if (state is BundleUpdaterState.BackingOff) {
                        assertEquals(event.retryNumber, state.retryNumber)
                        assertEquals(event.nextRetryTime, state.nextRetryTime)
                        backoffStateObserved = true
                    }
                }
            }
        }
        updater.close()

        assertTrue(backoffStateObserved, "Should observe BackingOff state during backoff")
    }

    @Test
    fun downloadLatest_alreadyUpToDate_noBackoff() = runBlocking {
        val fileContent = "test-content".toByteArray()
        val bundleFile = TestFixtures.createBundleFile("app.jar", fileContent)
        val manifest = TestFixtures.createSignedManifest(
            files = listOf(bundleFile),
            signer = keyPair.signer,
            buildNumber = 100 // Same as current
        )

        mockServer.enqueue(
            MockResponse()
                .setBody(json.encodeToString(manifest))
                .setHeader("Content-Type", "application/json")
        )

        val updater = createUpdater(currentBuildNumber = 100, maxAttempts = 3)
        val events = withContext(Dispatchers.IO) {
            updater.downloadLatest().toList()
        }
        updater.close()

        val upToDateEvents = events.filterIsInstance<BundleUpdateEvent.UpToDate>()
        assertEquals(1, upToDateEvents.size)

        val backoffEvents = events.filterIsInstance<BundleUpdateEvent.BackingOff>()
        assertTrue(backoffEvents.isEmpty())
    }

    // ========== Helper methods ==========

    private fun createUpdater(
        currentBuildNumber: Long,
        maxAttempts: Int = 3,
        initialDelay: kotlin.time.Duration = 1.seconds,
        multiplier: Double = 2.0,
    ): BundleUpdater {
        val retryConfig = RetryConfig(
            initialDelay = initialDelay,
            maxDelay = 60.seconds,
            multiplier = multiplier,
            maxAttempts = maxAttempts,
        )
        val config = BundleUpdaterConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = mockServer.url("/").toString().trimEnd('/'),
            publicKey = keyPair.publicKeyBase64,
            currentBuildNumber = currentBuildNumber,
            platform = Platform.fromString("macos-arm64"),
            retryConfig = retryConfig,
        )
        // Use no-op delay function for fast tests
        return BundleUpdater(config, fixedClock, delayFunction = { /* no-op */ })
    }

    private fun createBundleZip(
        files: List<io.runwork.bundle.common.manifest.BundleFile>,
        contents: Map<String, ByteArray>
    ): okio.Buffer {
        val buffer = okio.Buffer()
        java.util.zip.ZipOutputStream(buffer.outputStream()).use { zip ->
            for (file in files) {
                val content = contents[file.path]
                    ?: throw IllegalArgumentException("Missing content for ${file.path}")
                zip.putNextEntry(java.util.zip.ZipEntry(file.path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return buffer
    }
}
