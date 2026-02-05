package io.runwork.bundle.updater

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.common.verification.HashVerifier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test utilities for bundle-updater tests.
 */
object TestFixtures {

    /**
     * Create a temporary directory that will be cleaned up after the test.
     */
    fun createTempDir(prefix: String = "bundle-test"): Path {
        return Files.createTempDirectory(prefix)
    }

    /**
     * Create a test file with the given content.
     */
    fun createTestFile(dir: Path, name: String, content: ByteArray): Path {
        val path = dir.resolve(name)
        Files.createDirectories(path.parent)
        Files.write(path, content)
        return path
    }

    /**
     * Create a test file with string content.
     */
    fun createTestFile(dir: Path, name: String, content: String): Path {
        return createTestFile(dir, name, content.toByteArray())
    }

    /**
     * Create a test manifest with the given files.
     *
     * @param files List of bundle files
     * @param buildNumber Build number for the manifest
     * @param platforms List of platform IDs to include in zips (defaults to ["macos-arm64"])
     * @param mainClass Main class name
     * @param minShellVersion Minimum shell version required
     * @param shellUpdateUrl Optional shell update URL
     */
    fun createTestManifest(
        files: List<BundleFile>,
        buildNumber: Long = 1,
        platforms: List<String> = listOf("macos-arm64"),
        mainClass: String = "io.runwork.TestMain",
        minShellVersion: Int = 1,
        shellUpdateUrl: String? = null,
    ): BundleManifest {
        val size = files.sumOf { it.size }
        val zips = platforms.associateWith { platformId ->
            PlatformBundle(
                zip = "zips/bundle-$platformId.zip",
                size = size,
            )
        }

        return BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            createdAt = "2025-01-01T00:00:00Z",
            minShellVersion = minShellVersion,
            shellUpdateUrl = shellUpdateUrl,
            files = files,
            mainClass = mainClass,
            zips = zips,
            signature = ""
        )
    }

    /**
     * Create a signed test manifest.
     */
    fun createSignedManifest(
        files: List<BundleFile>,
        signer: TestBundleManifestSigner,
        buildNumber: Long = 1,
        platforms: List<String> = listOf("macos-arm64"),
        mainClass: String = "io.runwork.TestMain",
        minShellVersion: Int = 1,
        shellUpdateUrl: String? = null,
    ): BundleManifest {
        val unsigned = createTestManifest(files, buildNumber, platforms, mainClass, minShellVersion, shellUpdateUrl)
        return signer.signManifest(unsigned)
    }

    /**
     * Result of generating a test key pair.
     */
    data class TestKeyPair(
        val signer: TestBundleManifestSigner,
        val verifier: io.runwork.bundle.common.verification.SignatureVerifier,
        val publicKeyBase64: String,
    )

    /**
     * Generate a test key pair for signing/verification.
     */
    fun generateTestKeyPair(): TestKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val signer = TestBundleManifestSigner.fromBase64(privateKeyBase64)
        val verifier = io.runwork.bundle.common.verification.SignatureVerifier(publicKeyBase64)
        return TestKeyPair(signer, verifier, publicKeyBase64)
    }

    /**
     * Create a BundleFile with computed hash.
     */
    fun createBundleFile(
        path: String,
        content: ByteArray,
    ): BundleFile {
        val hash = computeHash(content)
        return BundleFile(
            path = path,
            hash = hash,
            size = content.size.toLong(),
        )
    }

    /**
     * Compute SHA-256 hash of content using Okio.
     */
    fun computeHash(content: ByteArray): BundleFileHash {
        return HashVerifier.computeHash(content)
    }

    /**
     * Compute SHA-256 hash of a file using Okio (streaming, memory-efficient).
     */
    suspend fun computeHash(path: Path): BundleFileHash {
        return HashVerifier.computeHash(path)
    }

    /**
     * Delete a directory recursively.
     */
    fun deleteRecursively(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }

    private val json = Json { prettyPrint = true }

    /**
     * Create a file-based bundle server directory structure for testing file:// URLs.
     *
     * Creates the following structure:
     * ```
     * baseDir/
     *   manifest.json           # Bundle manifest JSON
     *   bundle-{platform}.zip   # Per-platform bundle ZIPs (optional)
     *   files/
     *     <hash1>               # Individual files by hash hex
     *     <hash2>
     * ```
     *
     * @param baseDir The directory to create the bundle server in
     * @param manifest The bundle manifest to write
     * @param files Map of hash to file contents
     * @param includeZip If true, also creates platform-specific bundle.zip files
     * @return file:// URL pointing to the bundle server
     */
    fun createFileBundleServer(
        baseDir: Path,
        manifest: BundleManifest,
        files: Map<BundleFileHash, ByteArray>,
        includeZip: Boolean = false
    ): String {
        // Create directories
        val filesDir = baseDir.resolve("files")
        Files.createDirectories(filesDir)

        // Write manifest.json
        val manifestPath = baseDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Write individual files by hash hex
        for ((hash, content) in files) {
            val hashWithoutPrefix = hash.hex
            val filePath = filesDir.resolve(hashWithoutPrefix)
            Files.write(filePath, content)
        }

        // Optionally create platform-specific bundle zips
        if (includeZip) {
            for ((platformId, platformBundle) in manifest.zips) {
                val zipPath = baseDir.resolve(platformBundle.zip)
                Files.createDirectories(zipPath.parent)
                createBundleZip(zipPath, manifest, files)
            }
        }

        return baseDir.toUri().toString().trimEnd('/')
    }

    /**
     * Create a bundle.zip file containing the manifest files.
     *
     * Streams directly to file (memory-efficient for large bundles).
     */
    private fun createBundleZip(
        zipPath: Path,
        manifest: BundleManifest,
        files: Map<BundleFileHash, ByteArray>
    ) {
        // Stream directly to the ZIP file instead of buffering in memory
        // Deduplicate by hash so identical content is stored once in the zip
        Files.newOutputStream(zipPath).use { fos ->
            ZipOutputStream(fos).use { zos ->
                for (bundleFile in manifest.files.distinctBy { it.hash }) {
                    val content = files[bundleFile.hash]
                    if (content != null) {
                        zos.putNextEntry(ZipEntry(bundleFile.hash.hex))
                        zos.write(content)
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}

/**
 * Simple manifest signer for tests.
 */
class TestBundleManifestSigner private constructor(
    private val privateKeyBytes: ByteArray
) {
    companion object {
        fun fromBase64(privateKeyBase64: String): TestBundleManifestSigner {
            val privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64)
            return TestBundleManifestSigner(privateKeyBytes)
        }
    }

    private val json = BundleJson.signingJson

    fun sign(data: ByteArray): String {
        val keyFactory = KeyFactory.getInstance("Ed25519")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(data)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun signManifest(manifest: BundleManifest): BundleManifest {
        // Create manifest without signature for signing
        val unsignedManifest = manifest.copy(signature = "")

        // Sign the manifest JSON
        val jsonBytes = json.encodeToString(unsignedManifest).toByteArray()
        val signature = sign(jsonBytes)

        return unsignedManifest.copy(signature = "ed25519:$signature")
    }
}
