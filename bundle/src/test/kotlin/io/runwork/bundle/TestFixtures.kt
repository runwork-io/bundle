package io.runwork.bundle

import io.runwork.bundle.manifest.BundleFile
import io.runwork.bundle.manifest.BundleManifest
import io.runwork.bundle.manifest.FileType
import io.runwork.bundle.manifest.ManifestSigner
import io.runwork.bundle.verification.SignatureVerifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test utilities for bundle tests.
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
     */
    fun createTestManifest(
        files: List<BundleFile>,
        buildNumber: Long = 1,
        platform: String = "macos-arm64",
        mainClass: String = "io.runwork.TestMain",
    ): BundleManifest {
        return BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            platform = platform,
            createdAt = "2025-01-01T00:00:00Z",
            minimumShellVersion = 1,
            files = files,
            mainClass = mainClass,
            totalSize = files.sumOf { it.size },
            bundleHash = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            signature = ""
        )
    }

    /**
     * Create a signed test manifest.
     */
    fun createSignedManifest(
        files: List<BundleFile>,
        signer: ManifestSigner,
        buildNumber: Long = 1,
        platform: String = "macos-arm64",
        mainClass: String = "io.runwork.TestMain",
    ): BundleManifest {
        val unsigned = createTestManifest(files, buildNumber, platform, mainClass)
        return signer.signManifest(unsigned)
    }

    /**
     * Generate a test key pair for signing/verification.
     */
    fun generateTestKeyPair(): Pair<ManifestSigner, SignatureVerifier> {
        val (privateKey, publicKey) = ManifestSigner.generateKeyPair()
        val signer = ManifestSigner.fromBase64(privateKey)
        val verifier = SignatureVerifier(publicKey)
        return Pair(signer, verifier)
    }

    /**
     * Create a BundleFile with computed hash.
     */
    fun createBundleFile(
        path: String,
        content: ByteArray,
        type: FileType = FileType.RESOURCE
    ): BundleFile {
        val hash = computeHash(content)
        return BundleFile(
            path = path,
            hash = hash,
            size = content.size.toLong(),
            type = type
        )
    }

    /**
     * Compute SHA-256 hash of content.
     */
    fun computeHash(content: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content)
        return "sha256:" + hashBytes.joinToString("") { "%02x".format(it) }
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
}
