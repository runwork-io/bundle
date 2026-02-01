package io.runwork.bundle.creator.cli

import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.verification.SignatureVerifier
import io.runwork.bundle.creator.BundleManifestSigner
import io.runwork.bundle.creator.TestFixtures
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import java.util.zip.ZipFile

class BundleCreatorCliTest {

    private lateinit var tempDir: Path
    private lateinit var inputDir: File
    private lateinit var outputDir: File
    private lateinit var privateKeyFile: File
    private lateinit var publicKey: String

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("cli-test")
        inputDir = tempDir.resolve("input").toFile().also { it.mkdirs() }
        outputDir = tempDir.resolve("output").toFile().also { it.mkdirs() }

        // Generate key pair and save to file
        val (privateKey, pubKey) = BundleManifestSigner.generateKeyPair()
        publicKey = pubKey
        privateKeyFile = tempDir.resolve("private.key").toFile()
        privateKeyFile.writeText(privateKey)
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun generateKeys_outputsValidKeyPair() {
        val output = captureStdout {
            main(arrayOf("--generate-keys"))
        }

        // Output should contain both keys
        assertTrue(output.contains("Private Key"))
        assertTrue(output.contains("Public Key"))

        // Extract the keys from output
        val lines = output.lines()
        val privateKeyLine = lines.find { it.length > 50 && !it.contains(" ") && !it.contains(":") }
        val publicKeyLine = lines.findLast { it.length > 50 && !it.contains(" ") && !it.contains(":") }

        assertNotNull(privateKeyLine)
        assertNotNull(publicKeyLine)

        // Keys should be valid
        val signer = BundleManifestSigner.fromBase64(privateKeyLine.trim())
        val verifier = SignatureVerifier(publicKeyLine.trim())

        val data = "Test".toByteArray()
        val signature = signer.sign(data)
        assertTrue(verifier.verify(data, signature))
    }

    @Test
    fun createBundle_producesSignedManifest() {
        // Create input file
        File(inputDir, "test.txt").writeText("Test content")

        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath,
            "--build-number", "42"
        ))

        // Verify manifest exists and is signed
        val manifestFile = File(outputDir, "manifest.json")
        assertTrue(manifestFile.exists())

        val manifest = json.decodeFromString<BundleManifest>(manifestFile.readText())
        assertEquals(42, manifest.buildNumber)
        assertEquals("macos-arm64", manifest.platform)
        assertTrue(manifest.signature.startsWith("ed25519:"))

        // Verify signature is valid
        val verifier = SignatureVerifier(publicKey)
        assertTrue(verifier.verifyManifest(manifest))
    }

    @Test
    fun createBundle_producesBundleZip() {
        File(inputDir, "file1.txt").writeText("Content 1")
        File(inputDir, "file2.txt").writeText("Content 2")

        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath
        ))

        val bundleZip = File(outputDir, "bundle.zip")
        assertTrue(bundleZip.exists())

        // Verify ZIP contains the files
        ZipFile(bundleZip).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertTrue(entries.contains("file1.txt"))
            assertTrue(entries.contains("file2.txt"))
        }
    }

    @Test
    fun createBundle_producesHashNamedFiles() {
        val content = "Unique content"
        File(inputDir, "file.txt").writeText(content)

        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath
        ))

        val filesDir = File(outputDir, "files")
        assertTrue(filesDir.exists())
        assertTrue(filesDir.isDirectory)

        // Files should be named by hash (no sha256: prefix)
        val hash = TestFixtures.computeHash(content.toByteArray()).removePrefix("sha256:")
        val hashedFile = File(filesDir, hash)
        assertTrue(hashedFile.exists())
        assertEquals(content, hashedFile.readText())
    }

    @Test
    fun createBundle_detectsFileTypes() {
        File(inputDir, "app.jar").writeText("JAR content")
        File(inputDir, "natives").mkdirs()
        File(inputDir, "natives/lib.dylib").writeText("Native content")
        File(inputDir, "config.txt").writeText("Config content")

        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath
        ))

        val manifest = json.decodeFromString<BundleManifest>(
            File(outputDir, "manifest.json").readText()
        )

        val jarFile = manifest.files.find { it.path == "app.jar" }
        val nativeFile = manifest.files.find { it.path == "natives/lib.dylib" }
        val resourceFile = manifest.files.find { it.path == "config.txt" }

        assertNotNull(jarFile)
        assertNotNull(nativeFile)
        assertNotNull(resourceFile)

        assertEquals(io.runwork.bundle.common.manifest.FileType.JAR, jarFile.type)
        assertEquals(io.runwork.bundle.common.manifest.FileType.NATIVE, nativeFile.type)
        assertEquals(io.runwork.bundle.common.manifest.FileType.RESOURCE, resourceFile.type)
    }

    @Test
    fun createBundle_usesFilePrivateKey() {
        File(inputDir, "test.txt").writeText("Content")

        // Use private key from file
        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath
        ))

        val manifest = json.decodeFromString<BundleManifest>(
            File(outputDir, "manifest.json").readText()
        )

        val verifier = SignatureVerifier(publicKey)
        assertTrue(verifier.verifyManifest(manifest))
    }

    @Test
    fun createBundle_handlesNestedDirectories() {
        File(inputDir, "dir1/dir2").mkdirs()
        File(inputDir, "dir1/dir2/deep.txt").writeText("Deep content")

        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath
        ))

        val manifest = json.decodeFromString<BundleManifest>(
            File(outputDir, "manifest.json").readText()
        )

        val deepFile = manifest.files.find { it.path == "dir1/dir2/deep.txt" }
        assertNotNull(deepFile)
    }

    @Test
    fun createBundle_calculatesCorrectTotalSize() {
        File(inputDir, "file1.txt").writeText("A".repeat(100))
        File(inputDir, "file2.txt").writeText("B".repeat(200))

        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath
        ))

        val manifest = json.decodeFromString<BundleManifest>(
            File(outputDir, "manifest.json").readText()
        )

        assertEquals(300, manifest.totalSize)
    }

    @Test
    fun createBundle_normalizesPaths() {
        // Create files with platform-specific separators
        File(inputDir, "subdir").mkdirs()
        File(inputDir, "subdir/file.txt").writeText("Content")

        main(arrayOf(
            "--input", inputDir.absolutePath,
            "--output", outputDir.absolutePath,
            "--platform", "macos-arm64",
            "--private-key-path", privateKeyFile.absolutePath
        ))

        val manifest = json.decodeFromString<BundleManifest>(
            File(outputDir, "manifest.json").readText()
        )

        // Path should use forward slashes regardless of platform
        val file = manifest.files.find { it.path.contains("file.txt") }
        assertNotNull(file)
        assertTrue(file.path.contains("/"))
        assertTrue(!file.path.contains("\\"))
    }

    private fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        try {
            block()
        } finally {
            System.setOut(originalOut)
        }
        return baos.toString()
    }
}
