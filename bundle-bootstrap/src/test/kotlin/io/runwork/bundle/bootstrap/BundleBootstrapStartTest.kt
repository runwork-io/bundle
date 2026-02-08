package io.runwork.bundle.bootstrap

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.updater.download.DownloadProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundleBootstrapStartTest {

    private lateinit var tempDir: Path
    private lateinit var appDataDir: Path
    private lateinit var serverDir: Path
    private lateinit var keyPair: TestKeyPair

    private val json = BundleJson.decodingJson
    private val platform = "macos-arm64"

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("bootstrap-start-test")
        appDataDir = tempDir.resolve("app-data")
        serverDir = tempDir.resolve("server")
        Files.createDirectories(appDataDir)
        Files.createDirectories(serverDir)
        keyPair = generateTestKeyPair()
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun start_launchesExistingValidBundle() = runTest {
        // Set up a valid bundle on disk (no download needed)
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile))

        setupBundleOnDisk(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap()

        // Can't actually launch because test.txt isn't a JAR with a main class,
        // but we can verify the flow: it should try to launch and fail with Failed event
        val events = bootstrap.validateAndLaunch().collectEvents()
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(failed.reason.contains("not found", ignoreCase = true))
    }

    @Test
    fun start_downloadsAndLaunchesWhenNoBundleExists() = runTest {
        // Set up server with a bundle
        val fileContent = "test content for download"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), buildNumber = 100)

        setupBundleServer(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap()

        // Should download then attempt launch (fails because test.txt isn't a JAR)
        val events = bootstrap.validateAndLaunch().collectEvents()
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(failed.reason.contains("not found", ignoreCase = true))

        bootstrap.close()
    }

    @Test
    fun start_emitsShellUpdateRequired() = runTest {
        // Set up bundle that requires newer shell
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            minShellVersion = 10,
            shellUpdateUrl = "https://example.com/update"
        )

        setupBundleOnDisk(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap(shellVersion = 5)
        val events = bootstrap.validateAndLaunch().collectEvents()
        val shellUpdate = events.filterIsInstance<BundleStartEvent.ShellUpdateRequired>().single()

        assertEquals(5, shellUpdate.currentVersion)
        assertEquals(10, shellUpdate.requiredVersion)
        assertEquals("https://example.com/update", shellUpdate.updateUrl)
    }

    @Test
    fun start_emitsFailedForDownloadFailure() = runTest {
        // Server dir is empty — no manifest to download
        val bootstrap = createBootstrap(baseUrl = "file://${serverDir.toAbsolutePath()}")

        val events = bootstrap.validateAndLaunch().collectEvents()
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(failed.isRetryable)
    }

    @Test
    fun start_emitsFailedForSignatureError() = runTest {
        // Create manifest signed with wrong key
        val wrongKeyPair = generateTestKeyPair()
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            keyPairOverride = wrongKeyPair,
        )

        setupBundleServer(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        // Bootstrap uses different key than what signed the manifest
        val bootstrap = createBootstrap()

        // Download should fail because signature check fails
        val events = bootstrap.validateAndLaunch().collectEvents()
        assertTrue(events.any { it is BundleStartEvent.Failed })
    }

    @Test
    fun start_emitsProgressInOrder() = runTest {
        // Set up server with a bundle
        val fileContent = "progress test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), buildNumber = 100)

        setupBundleServer(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap()
        val events = bootstrap.validateAndLaunch().collectEvents()

        // Should see: ValidatingManifest → Downloading... → ValidatingManifest → Launching (then Failed because test.txt isn't a JAR)
        assertTrue(events.isNotEmpty())
        val progressEvents = events.filterIsInstance<BundleStartEvent.Progress>()
        assertIs<BundleStartEvent.Progress.ValidatingManifest>(progressEvents.first())

        val hasLaunching = progressEvents.any { it is BundleStartEvent.Progress.Launching }
        assertTrue(hasLaunching, "Expected Launching progress")

        // Verify ordering: ValidatingManifest before Launching
        val validatingIdx = progressEvents.indexOfFirst { it is BundleStartEvent.Progress.ValidatingManifest }
        val launchingIdx = progressEvents.indexOfFirst { it is BundleStartEvent.Progress.Launching }
        assertTrue(validatingIdx < launchingIdx, "ValidatingManifest should come before Launching")

        // After download, re-validation should emit ValidatingFiles with correct byte data
        val validatingFiles = progressEvents.filterIsInstance<BundleStartEvent.Progress.ValidatingFiles>()
        assertTrue(validatingFiles.isNotEmpty(), "Expected ValidatingFiles progress events")
        val lastValidating = validatingFiles.last()
        assertEquals(fileContent.toByteArray().size.toLong(), lastValidating.totalBytes)
        assertEquals(lastValidating.totalBytes, lastValidating.bytesVerified)

        bootstrap.close()
    }

    @Test
    fun start_recoversFromMissingBundleByDownloading() = runTest {
        // Set up a bundle on disk with missing CAS file (simulates partial corruption)
        val fileContent = "real content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile))

        // Write manifest but DON'T write the CAS file — simulates partial corruption
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        // CAS file missing — no Files.write here

        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)

        // Set up server with a newer bundle for re-download
        val serverManifest = createSignedManifest(listOf(bundleFile), buildNumber = 2)
        setupBundleServer(serverManifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap()

        // Should detect missing CAS file, download fresh, then attempt launch
        // (fails because test.txt isn't a JAR, but the download + revalidation should succeed)
        val events = bootstrap.validateAndLaunch().collectEvents()
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(
            failed.reason.contains("not found", ignoreCase = true) ||
                failed.reason.contains("Main class", ignoreCase = true),
            "Expected launch failure after recovery, got: ${failed.reason}"
        )

        bootstrap.close()
    }

    @Test
    fun downloadLatest_downloadsBundle() = runTest {
        // Set up server
        val fileContent = "download test"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), buildNumber = 50)

        setupBundleServer(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap()
        val progressEvents = mutableListOf<DownloadProgress>()
        val result = bootstrap.downloadLatest { progressEvents.add(it) }

        assertIs<io.runwork.bundle.updater.result.DownloadResult.Success>(result)

        bootstrap.close()
    }

    @Test
    fun close_isIdempotent() {
        val bootstrap = createBootstrap()
        // close without ever calling validateAndLaunch/downloadLatest should not fail
        bootstrap.close()
        bootstrap.close() // second close should also not fail
    }

    // ----- Helper methods -----

    private suspend fun Flow<BundleStartEvent>.collectEvents(): List<BundleStartEvent> = toList()

    private fun createBootstrap(
        shellVersion: Int = 100,
        baseUrl: String? = null,
    ): BundleBootstrap {
        val config = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = baseUrl ?: serverDir.toUri().toString().trimEnd('/'),
            publicKey = keyPair.publicKeyBase64,
            shellVersion = shellVersion,
            platform = Platform.fromString(platform),
            mainClass = "io.runwork.TestMain",
        )
        return BundleBootstrap(config)
    }

    private fun setupBundleOnDisk(manifest: BundleManifest, files: Map<BundleFileHash, ByteArray>) {
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)

        for (bundleFile in manifest.files) {
            val content = files[bundleFile.hash] ?: continue
            val casFile = casDir.resolve(bundleFile.hash.hex)
            Files.write(casFile, content)

            val versionFile = versionDir.resolve(bundleFile.path)
            Files.createDirectories(versionFile.parent)
            when (Os.current) {
                Os.WINDOWS -> Files.createLink(versionFile, casFile)
                Os.MACOS, Os.LINUX -> {
                    val relativeSource = versionFile.parent.relativize(casFile)
                    Files.createSymbolicLink(versionFile, relativeSource)
                }
            }
        }
    }

    private fun setupBundleServer(manifest: BundleManifest, files: Map<BundleFileHash, ByteArray>) {
        // Write manifest.json
        val manifestPath = serverDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Write individual files by hash hex
        val filesDir = serverDir.resolve("files")
        Files.createDirectories(filesDir)
        for ((hash, content) in files) {
            Files.write(filesDir.resolve(hash.hex), content)
        }

        // Create platform-specific bundle ZIP
        for ((_, platformBundle) in manifest.zips) {
            val zipPath = serverDir.resolve(platformBundle.zip)
            Files.createDirectories(zipPath.parent)
            ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
                for (bundleFile in manifest.files.distinctBy { it.hash }) {
                    val content = files[bundleFile.hash] ?: continue
                    zos.putNextEntry(ZipEntry(bundleFile.hash.hex))
                    zos.write(content)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun createBundleFile(path: String, content: ByteArray): BundleFile {
        val hash = HashVerifier.computeHash(content)
        return BundleFile(path = path, hash = hash, size = content.size.toLong())
    }

    private fun createSignedManifest(
        files: List<BundleFile>,
        buildNumber: Long = 1,
        minShellVersion: Int = 1,
        shellUpdateUrl: String? = null,
        keyPairOverride: TestKeyPair? = null,
    ): BundleManifest {
        val kp = keyPairOverride ?: keyPair
        val unsigned = BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            createdAt = "2025-01-01T00:00:00Z",
            minShellVersion = minShellVersion,
            shellUpdateUrl = shellUpdateUrl,
            files = files,
            mainClass = "io.runwork.TestMain",
            zips = mapOf(
                platform to PlatformBundle(
                    zip = "zips/bundle-$platform.zip",
                    size = files.sumOf { it.size }
                )
            ),
            signature = ""
        )

        val jsonBytes = BundleJson.signingJson.encodeToString(unsigned).toByteArray()
        val signature = kp.signer.sign(jsonBytes)
        return unsigned.copy(signature = "ed25519:$signature")
    }

    // ----- Test key pair -----

    data class TestKeyPair(
        val signer: TestSigner,
        val publicKeyBase64: String,
    )

    class TestSigner(private val privateKeyBytes: ByteArray) {
        fun sign(data: ByteArray): String {
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            val signature = Signature.getInstance("Ed25519")
            signature.initSign(privateKey)
            signature.update(data)
            return Base64.getEncoder().encodeToString(signature.sign())
        }
    }

    private fun generateTestKeyPair(): TestKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val kp = keyPairGenerator.generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        return TestKeyPair(TestSigner(kp.private.encoded), publicKeyBase64)
    }
}
