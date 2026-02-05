package io.runwork.bundle.bootstrap

import io.runwork.bundle.bootstrap.loader.BundleLoadException
import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.common.verification.HashVerifier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BootstrapTest {

    private lateinit var tempDir: Path
    private lateinit var appDataDir: Path
    private lateinit var keyPair: TestKeyPair

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("bootstrap-test")
        appDataDir = tempDir.resolve("app-data")
        Files.createDirectories(appDataDir)
        keyPair = generateTestKeyPair()
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun validate_returnsValidForCompleteBundle() = runTest {
        // Set up a valid bundle
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), keyPair)

        setupBundle(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.Valid>(result)
        assertEquals(manifest.buildNumber, result.manifest.buildNumber)
    }

    @Test
    fun validate_returnsNoBundleExistsWhenMissing() = runTest {
        // No manifest.json exists
        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.NoBundleExists>(result)
    }

    @Test
    fun validate_returnsNoBundleExistsForMissingVersionDir() = runTest {
        // Manifest exists but version directory is missing
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), keyPair)

        // Write manifest but don't create version directory
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.NoBundleExists>(result)
    }

    @Test
    fun validate_returnsFailedForBadSignature() = runTest {
        // Create manifest with invalid signature
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())

        // Create manifest and tamper with signature
        val manifest = createSignedManifest(listOf(bundleFile), keyPair)
        val tamperedManifest = manifest.copy(signature = "ed25519:invalid_signature_data")

        // Write tampered manifest
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(tamperedManifest))

        // Create version directory with files
        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)
        Files.write(versionDir.resolve("test.txt"), fileContent.toByteArray())

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.Failed>(result)
        assertTrue(result.reason.contains("signature", ignoreCase = true))
    }

    @Test
    fun validate_returnsFailedForPlatformMismatch() = runTest {
        // Create manifest for different platform
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            keyPair = keyPair,
            platform = "windows-x64" // Different from config platform
        )

        setupBundle(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap(platform = "macos-arm64")
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.Failed>(result)
        assertTrue(result.reason.contains("platform", ignoreCase = true))
        assertTrue(result.reason.contains("not supported", ignoreCase = true))
    }

    @Test
    fun validate_returnsShellUpdateRequiredForOldShell() = runTest {
        // Create manifest requiring newer shell
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            keyPair = keyPair,
            minShellVersion = 10,
            shellUpdateUrl = "https://example.com/update"
        )

        setupBundle(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap(shellVersion = 5) // Older than required
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.ShellUpdateRequired>(result)
        assertEquals(5, result.currentVersion)
        assertEquals(10, result.requiredVersion)
        assertEquals("https://example.com/update", result.updateUrl)
    }

    @Test
    fun validate_returnsFailedForMissingCasFile() = runTest {
        // Create manifest with file, but don't create the CAS file
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), keyPair)

        // Setup manifest and version directory but no CAS file
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)
        // NOTE: Not creating CAS file, so validation should fail

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.Failed>(result)
        assertTrue(result.reason.contains("verification failed", ignoreCase = true))
        assertTrue(result.failures.isNotEmpty())
        assertTrue(result.failures.any { it.reason == "CAS file missing" })
    }

    @Test
    fun validate_returnsFailedForCorruptedCasFile() = runTest {
        // Create manifest with one hash, but write CAS file with different content
        val originalContent = "original content"
        val bundleFile = createBundleFile("test.txt", originalContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), keyPair)

        // Setup manifest
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Create CAS directory and write WRONG content to CAS file
        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val hashFileName = bundleFile.hash.removePrefix("sha256:")
        Files.write(casDir.resolve(hashFileName), "corrupted content".toByteArray())

        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.Failed>(result)
        assertTrue(result.reason.contains("verification failed", ignoreCase = true))
        assertTrue(result.failures.isNotEmpty())
        assertTrue(result.failures.any { it.reason == "CAS file corrupted" })
    }

    @Test
    fun validate_repairsMissingVersionLink() = runTest {
        // Setup: CAS file exists, but version directory link is missing
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), keyPair)

        // Write manifest
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Create CAS file
        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val hashFileName = bundleFile.hash.removePrefix("sha256:")
        val casFile = casDir.resolve(hashFileName)
        Files.write(casFile, fileContent.toByteArray())

        // Create version directory but NO file link
        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)

        // Validate should repair the missing link
        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.Valid>(result)

        // Verify the link was created
        val versionFile = versionDir.resolve("test.txt")
        assertTrue(Files.exists(versionFile))
        assertEquals(fileContent, Files.readString(versionFile))
    }

    @Test
    fun validate_repairsBrokenVersionLink() = runTest {
        // Setup: CAS file exists, version file exists but is NOT linked to CAS (different content)
        val originalContent = "original content"
        val bundleFile = createBundleFile("test.txt", originalContent.toByteArray())
        val manifest = createSignedManifest(listOf(bundleFile), keyPair)

        // Write manifest
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Create CAS file with correct content
        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val hashFileName = bundleFile.hash.removePrefix("sha256:")
        val casFile = casDir.resolve(hashFileName)
        Files.write(casFile, originalContent.toByteArray())

        // Create version directory with WRONG content (not linked to CAS)
        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)
        val versionFile = versionDir.resolve("test.txt")
        Files.write(versionFile, "wrong content".toByteArray())

        // Validate should repair the broken link
        val bootstrap = createBootstrap()
        val result = bootstrap.validate()

        assertIs<BundleValidationResult.Valid>(result)

        // Verify the link was repaired with correct content
        assertEquals(originalContent, Files.readString(versionFile))
    }

    @Test
    fun launch_throwsForMissingMainClass() = runTest {
        // Create a valid manifest pointing to a non-existent main class
        val fileContent = "test content"
        val bundleFile = createBundleFile("test.txt", fileContent.toByteArray())
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            keyPair = keyPair,
            mainClass = "com.nonexistent.MainClass"
        )

        setupBundle(manifest, mapOf(bundleFile.hash to fileContent.toByteArray()))

        val bootstrap = createBootstrap()
        val validResult = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(validResult)

        val exception = assertFailsWith<BundleLoadException> {
            bootstrap.launch(validResult)
        }

        assertTrue(exception.message?.contains("not found", ignoreCase = true) == true)
    }

    @Test
    fun launch_throwsForNonStaticMain() = runTest {
        // Create a JAR with a class that has non-static main method
        val jarPath = createJarWithNonStaticMain()
        val jarContent = Files.readAllBytes(jarPath)
        val bundleFile = createBundleFile("app.jar", jarContent)
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            keyPair = keyPair,
            mainClass = "com.test.NonStaticMain"
        )

        setupBundle(manifest, mapOf(bundleFile.hash to jarContent))

        val bootstrap = createBootstrap()
        val validResult = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(validResult)

        val exception = assertFailsWith<BundleLoadException> {
            bootstrap.launch(validResult)
        }

        assertTrue(exception.message?.contains("static", ignoreCase = true) == true)
    }

    // ----- Helper methods -----

    private fun createBootstrap(
        platform: String = "macos-arm64",
        shellVersion: Int = 100
    ): BundleBootstrap {
        val config = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = "https://example.com",
            publicKey = keyPair.publicKeyBase64,
            shellVersion = shellVersion,
            platform = Platform.fromString(platform),
            mainClass = "io.runwork.TestMain",
        )
        return BundleBootstrap(config)
    }

    private fun setupBundle(manifest: BundleManifest, files: Map<String, ByteArray>) {
        // Write manifest.json
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Create CAS directory and version directory
        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)

        // Write files to CAS and link to version directory
        for (bundleFile in manifest.files) {
            val content = files[bundleFile.hash]
            if (content != null) {
                // Write to CAS (filename is the hash without "sha256:" prefix)
                val hashFileName = bundleFile.hash.removePrefix("sha256:")
                val casFile = casDir.resolve(hashFileName)
                Files.write(casFile, content)

                // Create link in version directory (symlink on macOS/Linux, hard link on Windows)
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
    }

    private fun createBundleFile(
        path: String,
        content: ByteArray,
    ): BundleFile {
        val hash = HashVerifier.computeHash(content)
        return BundleFile(
            path = path,
            hash = hash,
            size = content.size.toLong(),
        )
    }

    private fun createSignedManifest(
        files: List<BundleFile>,
        keyPair: TestKeyPair,
        buildNumber: Long = 1,
        platform: String = "macos-arm64",
        mainClass: String = "io.runwork.TestMain",
        minShellVersion: Int = 1,
        shellUpdateUrl: String? = null
    ): BundleManifest {
        val unsigned = BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            createdAt = "2025-01-01T00:00:00Z",
            minShellVersion = minShellVersion,
            shellUpdateUrl = shellUpdateUrl,
            files = files,
            mainClass = mainClass,
            platformBundles = mapOf(
                platform to PlatformBundle(
                    zip = "bundle-$platform.zip",
                    size = files.sumOf { it.size }
                )
            ),
            signature = ""
        )

        // Sign the manifest
        val jsonBytes = BundleJson.signingJson.encodeToString(unsigned).toByteArray()
        val signature = keyPair.signer.sign(jsonBytes)

        return unsigned.copy(signature = "ed25519:$signature")
    }

    private fun createJarWithNonStaticMain(): Path {
        val jarPath = tempDir.resolve("non-static-main.jar")

        // Create minimal bytecode for a class with non-static main method
        val classBytes = createClassWithNonStaticMain()

        java.util.jar.JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("com/test/NonStaticMain.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }

        return jarPath
    }

    /**
     * Creates minimal bytecode for a class with non-static main(String[]) method.
     */
    private fun createClassWithNonStaticMain(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()

        // Magic number
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        // Version (Java 8: major=52, minor=0)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count (10 entries, 1-indexed so count=11)
        baos.write(byteArrayOf(0x00, 0x0B))

        // #1: CONSTANT_Class -> #2 (this class)
        baos.write(byteArrayOf(0x07, 0x00, 0x02))

        // #2: CONSTANT_Utf8 -> com/test/NonStaticMain
        val className = "com/test/NonStaticMain".toByteArray()
        baos.write(byteArrayOf(0x01, (className.size shr 8).toByte(), className.size.toByte()))
        baos.write(className)

        // #3: CONSTANT_Class -> #4 (java/lang/Object)
        baos.write(byteArrayOf(0x07, 0x00, 0x04))

        // #4: CONSTANT_Utf8 -> java/lang/Object
        val objectName = "java/lang/Object".toByteArray()
        baos.write(byteArrayOf(0x01, (objectName.size shr 8).toByte(), objectName.size.toByte()))
        baos.write(objectName)

        // #5: CONSTANT_Utf8 -> main
        val mainName = "main".toByteArray()
        baos.write(byteArrayOf(0x01, (mainName.size shr 8).toByte(), mainName.size.toByte()))
        baos.write(mainName)

        // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
        val mainDesc = "([Ljava/lang/String;)V".toByteArray()
        baos.write(byteArrayOf(0x01, (mainDesc.size shr 8).toByte(), mainDesc.size.toByte()))
        baos.write(mainDesc)

        // #7: CONSTANT_Utf8 -> Code
        val codeName = "Code".toByteArray()
        baos.write(byteArrayOf(0x01, (codeName.size shr 8).toByte(), codeName.size.toByte()))
        baos.write(codeName)

        // #8: CONSTANT_Utf8 -> <init>
        val initName = "<init>".toByteArray()
        baos.write(byteArrayOf(0x01, (initName.size shr 8).toByte(), initName.size.toByte()))
        baos.write(initName)

        // #9: CONSTANT_Utf8 -> ()V
        val voidDesc = "()V".toByteArray()
        baos.write(byteArrayOf(0x01, (voidDesc.size shr 8).toByte(), voidDesc.size.toByte()))
        baos.write(voidDesc)

        // #10: CONSTANT_Methodref -> #3.#11 (Object.<init>)
        baos.write(byteArrayOf(0x0A, 0x00, 0x03, 0x00, 0x0B))

        // #11 is actually a NameAndType, need to fix constant pool structure
        // Let me restart with correct structure...

        return createClassWithNonStaticMainV2()
    }

    /**
     * Creates proper minimal bytecode for a class with non-static main(String[]) method.
     */
    private fun createClassWithNonStaticMainV2(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()

        // Magic number
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        // Version (Java 8: major=52, minor=0)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count = 13 (indices 1-12)
        baos.write(byteArrayOf(0x00, 0x0D))

        // #1: CONSTANT_Class -> #2 (this class)
        baos.write(byteArrayOf(0x07, 0x00, 0x02))

        // #2: CONSTANT_Utf8 -> com/test/NonStaticMain
        val className = "com/test/NonStaticMain".toByteArray()
        baos.write(byteArrayOf(0x01, (className.size shr 8).toByte(), className.size.toByte()))
        baos.write(className)

        // #3: CONSTANT_Class -> #4 (java/lang/Object)
        baos.write(byteArrayOf(0x07, 0x00, 0x04))

        // #4: CONSTANT_Utf8 -> java/lang/Object
        val objectName = "java/lang/Object".toByteArray()
        baos.write(byteArrayOf(0x01, (objectName.size shr 8).toByte(), objectName.size.toByte()))
        baos.write(objectName)

        // #5: CONSTANT_Utf8 -> main
        val mainName = "main".toByteArray()
        baos.write(byteArrayOf(0x01, (mainName.size shr 8).toByte(), mainName.size.toByte()))
        baos.write(mainName)

        // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
        val mainDesc = "([Ljava/lang/String;)V".toByteArray()
        baos.write(byteArrayOf(0x01, (mainDesc.size shr 8).toByte(), mainDesc.size.toByte()))
        baos.write(mainDesc)

        // #7: CONSTANT_Utf8 -> Code
        val codeName = "Code".toByteArray()
        baos.write(byteArrayOf(0x01, (codeName.size shr 8).toByte(), codeName.size.toByte()))
        baos.write(codeName)

        // #8: CONSTANT_Utf8 -> <init>
        val initName = "<init>".toByteArray()
        baos.write(byteArrayOf(0x01, (initName.size shr 8).toByte(), initName.size.toByte()))
        baos.write(initName)

        // #9: CONSTANT_Utf8 -> ()V
        val voidDesc = "()V".toByteArray()
        baos.write(byteArrayOf(0x01, (voidDesc.size shr 8).toByte(), voidDesc.size.toByte()))
        baos.write(voidDesc)

        // #10: CONSTANT_Methodref -> #3.#11 (Object.<init>)
        baos.write(byteArrayOf(0x0A, 0x00, 0x03, 0x00, 0x0B))

        // #11: CONSTANT_NameAndType -> #8:#9 (<init>:()V)
        baos.write(byteArrayOf(0x0C, 0x00, 0x08, 0x00, 0x09))

        // #12: CONSTANT_NameAndType -> #5:#6 (main:([Ljava/lang/String;)V)
        baos.write(byteArrayOf(0x0C, 0x00, 0x05, 0x00, 0x06))

        // Access flags: public (0x0021)
        baos.write(byteArrayOf(0x00, 0x21))

        // This class (#1)
        baos.write(byteArrayOf(0x00, 0x01))

        // Super class (#3 = Object)
        baos.write(byteArrayOf(0x00, 0x03))

        // Interfaces count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        // Fields count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        // Methods count (2: constructor and main)
        baos.write(byteArrayOf(0x00, 0x02))

        // Method 1: <init>()V - public constructor
        // Access flags: public (0x0001)
        baos.write(byteArrayOf(0x00, 0x01))
        // Name index (#8 = <init>)
        baos.write(byteArrayOf(0x00, 0x08))
        // Descriptor index (#9 = ()V)
        baos.write(byteArrayOf(0x00, 0x09))
        // Attributes count (1 = Code)
        baos.write(byteArrayOf(0x00, 0x01))
        // Code attribute
        // Attribute name index (#7 = Code)
        baos.write(byteArrayOf(0x00, 0x07))
        // Attribute length (17 bytes)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x11))
        // Max stack (1)
        baos.write(byteArrayOf(0x00, 0x01))
        // Max locals (1)
        baos.write(byteArrayOf(0x00, 0x01))
        // Code length (5)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x05))
        // Code: aload_0, invokespecial Object.<init>, return
        baos.write(byteArrayOf(0x2A, 0xB7.toByte(), 0x00, 0x0A, 0xB1.toByte()))
        // Exception table length (0)
        baos.write(byteArrayOf(0x00, 0x00))
        // Code attributes count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        // Method 2: main(String[])V - public but NOT static
        // Access flags: public (0x0001) - NOTE: not static (0x0008)
        baos.write(byteArrayOf(0x00, 0x01))
        // Name index (#5 = main)
        baos.write(byteArrayOf(0x00, 0x05))
        // Descriptor index (#6 = ([Ljava/lang/String;)V)
        baos.write(byteArrayOf(0x00, 0x06))
        // Attributes count (1 = Code)
        baos.write(byteArrayOf(0x00, 0x01))
        // Code attribute
        // Attribute name index (#7 = Code)
        baos.write(byteArrayOf(0x00, 0x07))
        // Attribute length (13 bytes)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x0D))
        // Max stack (0)
        baos.write(byteArrayOf(0x00, 0x00))
        // Max locals (2)
        baos.write(byteArrayOf(0x00, 0x02))
        // Code length (1)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        // Code: return
        baos.write(byteArrayOf(0xB1.toByte()))
        // Exception table length (0)
        baos.write(byteArrayOf(0x00, 0x00))
        // Code attributes count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        // Class attributes count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        return baos.toByteArray()
    }

    // ----- Test key pair helpers -----

    data class TestKeyPair(
        val signer: TestSigner,
        val publicKeyBase64: String
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
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKeyBytes = keyPair.private.encoded
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return TestKeyPair(TestSigner(privateKeyBytes), publicKeyBase64)
    }
}
