package io.runwork.bundle.integration

import io.runwork.bundle.BundleManager
import io.runwork.bundle.BundleVerificationResult
import io.runwork.bundle.TestFixtures
import io.runwork.bundle.UpdateCheckResult
import io.runwork.bundle.testing.TestBundle
import io.runwork.bundle.testing.TestBundleClient
import io.runwork.bundle.testing.TestBundleServer
import io.runwork.bundle.testing.assertCorrupted
import io.runwork.bundle.testing.assertFailure
import io.runwork.bundle.testing.assertSuccess
import io.runwork.bundle.testing.assertUpToDate
import io.runwork.bundle.testing.assertUpdateAvailable
import io.runwork.bundle.testing.assertValid
import io.runwork.bundle.testing.assertNoBundleInstalled
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the bundle system using file:// URLs.
 *
 * These tests exercise the full download/update flow without MockWebServer,
 * using the new TestBundle, TestBundleServer, and TestBundleClient APIs.
 */
class BundleIntegrationTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("bundle-integration-test")
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    /**
     * Helper to run test with proper BundleManager cleanup.
     */
    private inline fun <T> BundleManager.use(block: (BundleManager) -> T): T {
        return try {
            block(this)
        } finally {
            close()
        }
    }

    // ========== Basic Scenarios ==========

    @Test
    fun freshInstall_downloadsAndInstallsBundle() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Application content")
            resource("config.json", """{"version": 1}""")
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        // Include ZIP for full bundle download (fresh install with small files will use full strategy)
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            // Check for update
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable { info ->
                assertEquals(1, info.buildNumber)
            }

            // Download
            val downloadResult = bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}
            downloadResult.assertSuccess()

            // Verify
            bundleManager.verifyLocalBundle().assertValid()
        }
    }

    @Test
    fun updateAvailable_detectsNewerVersion() = runTest {
        val v1 = TestBundle.create {
            jar("app.jar", "v1 content")
            buildNumber = 1
        }

        val v2 = v1.withUpdatedFiles {
            jar("app.jar", "v2 content - new features!")
            buildNumber = 2
        }

        // Install v1 on client
        TestBundleClient.create(tempDir.resolve("app")) {
            install(v1)
        }

        // Serve v2
        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(v2)

        val config = server.bundleConfig(
            publicKey = v2.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            bundleManager.checkForUpdate().assertUpdateAvailable { info ->
                assertEquals(2, info.buildNumber)
                assertEquals(1, info.currentBuildNumber)
            }
        }
    }

    @Test
    fun upToDate_noUpdateNeeded() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Current content")
            buildNumber = 42
        }

        // Install bundle on client
        TestBundleClient.create(tempDir.resolve("app")) {
            install(bundle)
        }

        // Serve same version
        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            bundleManager.checkForUpdate().assertUpToDate { buildNumber ->
                assertEquals(42, buildNumber)
            }
        }
    }

    @Test
    fun incrementalUpdate_onlyDownloadsMissingFiles() = runTest {
        // Create v1 with large files (1MB each) to force incremental strategy
        val largeContent = ByteArray(1_000_000) { 'X'.code.toByte() }
        val v1 = TestBundle.create {
            jar("lib1.jar", largeContent)
            jar("lib2.jar", largeContent)
            jar("lib3.jar", largeContent)
            jar("app.jar", "v1 app")
            buildNumber = 1
        }

        // v2 updates app.jar only
        val newAppContent = "v2 app - updated!"
        val v2 = v1.withUpdatedFiles {
            jar("lib1.jar", largeContent)
            jar("lib2.jar", largeContent)
            jar("lib3.jar", largeContent)
            jar("app.jar", newAppContent)
            buildNumber = 2
        }

        // Install v1 (files in CAS)
        TestBundleClient.create(tempDir.resolve("app")) {
            install(v1)
        }

        // Serve v2
        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(v2)

        val config = server.bundleConfig(
            publicKey = v2.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            // Should use incremental strategy
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            // Verify new version is installed
            bundleManager.verifyLocalBundle().assertValid { manifest ->
                assertEquals(2, manifest.buildNumber)
            }
        }
    }

    @Test
    fun fullBundleDownload_downloadsZip() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Application content")
            jar("lib.jar", "Library content")
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            bundleManager.verifyLocalBundle().assertValid()
        }
    }

    // ========== Error Scenarios ==========

    @Test
    fun missingServerFile_failsDownload() = runTest {
        // Use large existing files to force incremental strategy for the missing file
        val largeContent = ByteArray(1_000_000) { 'X'.code.toByte() }
        val bundle = TestBundle.create {
            jar("lib1.jar", largeContent)
            jar("lib2.jar", largeContent)
            jar("lib3.jar", largeContent)
            jar("missing.jar", "Small missing file")
            buildNumber = 1
        }

        // Install a version with the large files so they're in CAS
        val v0 = TestBundle.create {
            jar("lib1.jar", largeContent)
            jar("lib2.jar", largeContent)
            jar("lib3.jar", largeContent)
            buildNumber = 0
        }
        TestBundleClient.create(tempDir.resolve("app")) {
            addToCas(v0)
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle)

        // Remove the missing file from server (this forces incremental to fail)
        server.removeFile(bundle.hashFor("missing.jar")!!)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertFailure { error ->
                assertTrue(error.contains("not found", ignoreCase = true))
            }
        }
    }

    @Test
    fun corruptedServerFile_failsHashVerification() = runTest {
        // Use large existing files to force incremental strategy for the corrupted file
        val largeContent = ByteArray(1_000_000) { 'X'.code.toByte() }
        val bundle = TestBundle.create {
            jar("lib1.jar", largeContent)
            jar("lib2.jar", largeContent)
            jar("lib3.jar", largeContent)
            jar("corrupted.jar", "Small file to corrupt")
            buildNumber = 1
        }

        // Install a version with the large files so they're in CAS
        val v0 = TestBundle.create {
            jar("lib1.jar", largeContent)
            jar("lib2.jar", largeContent)
            jar("lib3.jar", largeContent)
            buildNumber = 0
        }
        TestBundleClient.create(tempDir.resolve("app")) {
            addToCas(v0)
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle)

        // Corrupt the file on server
        server.corruptFile(bundle.hashFor("corrupted.jar")!!)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertFailure { error ->
                assertTrue(error.contains("hash", ignoreCase = true) || error.contains("mismatch", ignoreCase = true))
            }
        }
    }

    // ========== Verification Scenarios ==========

    @Test
    fun verifyLocalBundle_detectsCorruptedFile() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Original content")
            buildNumber = 42
        }

        val client = TestBundleClient.create(tempDir.resolve("app")) {
            install(bundle)
        }

        // Corrupt the installed file
        client.corruptFile(42, "app.jar")

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            // Verify should fail
            bundleManager.verifyLocalBundle().assertCorrupted { failures ->
                assertEquals(1, failures.size)
                assertEquals("app.jar", failures[0].path)
            }
        }
    }

    @Test
    fun verifyLocalBundle_detectsMissingFile() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Application content")
            buildNumber = 42
        }

        val client = TestBundleClient.create(tempDir.resolve("app")) {
            install(bundle)
        }

        // Delete the installed file
        client.deleteFile(42, "app.jar")

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            bundleManager.verifyLocalBundle().assertCorrupted { failures ->
                assertEquals(1, failures.size)
                assertEquals("app.jar", failures[0].path)
                assertEquals("File missing", failures[0].reason)
            }
        }
    }

    @Test
    fun verifyLocalBundle_noBundleInstalled() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Content")
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            bundleManager.verifyLocalBundle().assertNoBundleInstalled()
        }
    }

    // ========== Repair Scenarios ==========

    @Test
    fun corruptedBundle_repairsFromCas() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Application content")
            buildNumber = 42
        }

        val client = TestBundleClient.create(tempDir.resolve("app")) {
            install(bundle)
        }

        // Delete the hard link in version directory (simulates missing file)
        // We delete rather than corrupt because hard links share content with CAS,
        // and the file in CAS can be used to repair
        client.deleteFile(42, "app.jar")

        // Server has the files available for re-download if needed
        // Include ZIP since repair with small files may choose full bundle strategy
        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            // Verify should fail (file missing)
            bundleManager.verifyLocalBundle().assertCorrupted { failures ->
                assertEquals(1, failures.size)
                assertEquals("app.jar", failures[0].path)
            }

            // Repair should succeed - file is still in CAS, just needs re-linking
            val verifyResult = bundleManager.verifyLocalBundle() as BundleVerificationResult.Corrupted
            bundleManager.repairBundle(verifyResult.failures) {}.assertSuccess()

            // Now verification should pass
            bundleManager.verifyLocalBundle().assertValid()
        }
    }

    @Test
    fun repairBundle_redownloadsMissingFile() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "Application content")
            buildNumber = 42
        }

        val client = TestBundleClient.create(tempDir.resolve("app")) {
            install(bundle)
        }

        // Delete the installed file
        client.deleteFile(42, "app.jar")

        // Include ZIP since repair with small files may choose full bundle strategy
        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            // Verify should fail
            val verifyResult = bundleManager.verifyLocalBundle()
            verifyResult.assertCorrupted()

            // Repair should succeed
            bundleManager.repairBundle((verifyResult as BundleVerificationResult.Corrupted).failures) {}.assertSuccess()

            // Now verification should pass
            bundleManager.verifyLocalBundle().assertValid()
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun nestedPaths_handledCorrectly() = runTest {
        val bundle = TestBundle.create {
            jar("lib/core.jar", "Core library")
            native("natives/macos/libfoo.dylib", "Native library content")
            resource("config/app/settings.json", """{"key": "value"}""")
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            bundleManager.verifyLocalBundle().assertValid { manifest ->
                assertEquals(3, manifest.files.size)
            }
        }
    }

    @Test
    fun multipleFileTypes_handledCorrectly() = runTest {
        val bundle = TestBundle.create {
            jar("app.jar", "JAR content")
            native("lib.dylib", "Native content")
            resource("config.json", """{"key": "value"}""")
            executable("bin/tool", "Executable content")
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            bundleManager.verifyLocalBundle().assertValid { manifest ->
                assertEquals(4, manifest.files.size)
            }
        }
    }

    @Test
    fun withBuildNumber_changesOnlyBuildNumber() = runTest {
        val v1 = TestBundle.create {
            jar("app.jar", "Content")
            buildNumber = 1
        }

        val v2 = v1.withBuildNumber(2)

        assertEquals(1, v1.manifest.buildNumber)
        assertEquals(2, v2.manifest.buildNumber)

        // Content should be the same
        assertEquals(v1.hashFor("app.jar"), v2.hashFor("app.jar"))
    }

    @Test
    fun emptyBundle_handledCorrectly() = runTest {
        val bundle = TestBundle.create {
            buildNumber = 1
            // No files
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            bundleManager.verifyLocalBundle().assertValid { manifest ->
                assertEquals(0, manifest.files.size)
            }
        }
    }

    @Test
    fun largeFile_handledCorrectly() = runTest {
        val bundle = TestBundle.create {
            largeFile("large.bin", 500_000) // 500KB
            buildNumber = 1
        }

        // Include ZIP for fresh install with one large file (full bundle strategy)
        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )
        BundleManager(config).use { bundleManager ->
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            bundleManager.verifyLocalBundle().assertValid()
        }
    }
}
