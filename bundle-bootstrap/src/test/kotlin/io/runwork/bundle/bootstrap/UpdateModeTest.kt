package io.runwork.bundle.bootstrap

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.common.verification.HashVerifier
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateModeTest {

    private lateinit var tempDir: Path
    private lateinit var appDataDir: Path
    private lateinit var serverDir: Path
    private lateinit var keyPair: TestKeyPair

    private val json = BundleJson.decodingJson
    private val platformStr = "macos-arm64"

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("update-mode-test")
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

    // ============ RequireLatest Tests ============

    @Test
    fun requireLatest_downloadsAndLaunches() = runTest {
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), buildNumber = 100)

        setupBundleServer(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap(updateMode = UpdateMode.RequireLatest)
        val events = bootstrap.validateAndLaunch().collectEvents()

        // Should see CheckingForUpdates first
        val progressEvents = events.filterIsInstance<BundleStartEvent.Progress>()
        assertTrue(progressEvents.any { it is BundleStartEvent.Progress.CheckingForUpdates })

        // Should attempt to launch (fails because test.txt isn't a JAR)
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(
            failed.reason.contains("not found", ignoreCase = true),
            "Expected launch failure, got: ${failed.reason}"
        )

        bootstrap.close()
    }

    @Test
    fun requireLatest_failsWhenNetworkUnavailable() = runTest {
        // Set up a valid bundle on disk
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile))

        setupBundleOnDisk(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        // Server dir is empty — network fails
        val bootstrap = createBootstrap(
            updateMode = UpdateMode.RequireLatest,
            baseUrl = "file://${serverDir.toAbsolutePath()}"
        )
        val events = bootstrap.validateAndLaunch().collectEvents()

        // Should fail even though a valid local bundle exists
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(failed.isRetryable)

        bootstrap.close()
    }

    // ============ CheckOnLaunch Tests ============

    @Test
    fun checkOnLaunch_downloadsAndLaunches() = runTest {
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), buildNumber = 100)

        setupBundleServer(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap(updateMode = UpdateMode.CheckOnLaunch)
        val events = bootstrap.validateAndLaunch().collectEvents()

        // Should see CheckingForUpdates
        val progressEvents = events.filterIsInstance<BundleStartEvent.Progress>()
        assertTrue(progressEvents.any { it is BundleStartEvent.Progress.CheckingForUpdates })

        // Should attempt to launch
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(
            failed.reason.contains("not found", ignoreCase = true),
            "Expected launch failure, got: ${failed.reason}"
        )

        bootstrap.close()
    }

    @Test
    fun checkOnLaunch_fallsBackToExistingBundle() = runTest {
        // Set up a valid bundle on disk
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile))

        setupBundleOnDisk(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        // Server is empty — network fails, but should fall back to existing bundle
        val bootstrap = createBootstrap(
            updateMode = UpdateMode.CheckOnLaunch,
            baseUrl = "file://${serverDir.toAbsolutePath()}"
        )
        val events = bootstrap.validateAndLaunch().collectEvents()

        // Should attempt to launch the existing bundle
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(
            failed.reason.contains("not found", ignoreCase = true),
            "Expected launch failure (test.txt isn't a JAR), got: ${failed.reason}"
        )

        bootstrap.close()
    }

    @Test
    fun checkOnLaunch_failsWhenNoBundleAndNoNetwork() = runTest {
        // No bundle on disk, server is empty
        val bootstrap = createBootstrap(
            updateMode = UpdateMode.CheckOnLaunch,
            baseUrl = "file://${serverDir.toAbsolutePath()}"
        )
        val events = bootstrap.validateAndLaunch().collectEvents()

        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(
            failed.reason.contains("no valid local bundle", ignoreCase = true),
            "Expected 'no valid local bundle' failure, got: ${failed.reason}"
        )

        bootstrap.close()
    }

    // ============ Manual Tests ============

    @Test
    fun manual_behavesLikeDefault() = runTest {
        // Set up a valid bundle on disk
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile))

        setupBundleOnDisk(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap(updateMode = UpdateMode.Manual)
        val events = bootstrap.validateAndLaunch().collectEvents()

        // Should not emit CheckingForUpdates
        val progressEvents = events.filterIsInstance<BundleStartEvent.Progress>()
        assertTrue(progressEvents.none { it is BundleStartEvent.Progress.CheckingForUpdates })

        // Should attempt to launch
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(
            failed.reason.contains("not found", ignoreCase = true),
            "Expected launch failure, got: ${failed.reason}"
        )
    }

    // ============ Background Tests ============

    @Test
    fun background_launchesLikeManual() = runTest {
        // Set up server with a bundle
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), buildNumber = 100)

        setupBundleServer(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap(updateMode = UpdateMode.Background())
        val events = bootstrap.validateAndLaunch().collectEvents()

        // Should not emit CheckingForUpdates (background mode is like Manual for initial launch)
        val progressEvents = events.filterIsInstance<BundleStartEvent.Progress>()
        assertTrue(progressEvents.none { it is BundleStartEvent.Progress.CheckingForUpdates })

        // Should attempt to launch (fails because test.txt isn't a JAR)
        val failed = events.filterIsInstance<BundleStartEvent.Failed>().single()
        assertTrue(
            failed.reason.contains("not found", ignoreCase = true),
            "Expected launch failure, got: ${failed.reason}"
        )

        bootstrap.close()
    }

    // ----- Helper methods -----

    private suspend fun Flow<BundleStartEvent>.collectEvents(): List<BundleStartEvent> = toList()

    private fun createBootstrap(
        updateMode: UpdateMode = UpdateMode.Manual,
        baseUrl: String? = null,
        shellVersion: Int = 100,
    ): BundleBootstrap {
        val config = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = baseUrl ?: serverDir.toUri().toString().trimEnd('/'),
            publicKey = keyPair.publicKeyBase64,
            shellVersion = shellVersion,
            platform = Platform.fromString(platformStr),
            mainClass = "io.runwork.TestMain",
            updateMode = updateMode,
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
        val manifestPath = serverDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        val filesDir = serverDir.resolve("files")
        Files.createDirectories(filesDir)
        for ((hash, content) in files) {
            Files.write(filesDir.resolve(hash.hex), content)
        }

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
    ): BundleManifest {
        val unsigned = BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            createdAt = "2025-01-01T00:00:00Z",
            minShellVersion = 1,
            files = files,
            mainClass = "io.runwork.TestMain",
            zips = mapOf(
                platformStr to PlatformBundle(
                    zip = "zips/bundle-$platformStr.zip",
                    size = files.sumOf { it.size }
                )
            ),
            signature = ""
        )

        val jsonBytes = BundleJson.signingJson.encodeToString(unsigned).toByteArray()
        val signature = keyPair.signer.sign(jsonBytes)
        return unsigned.copy(signature = "ed25519:$signature")
    }

    // ----- Key pair helpers -----

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
