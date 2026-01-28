package io.runwork.bundle.testing

import io.runwork.bundle.TestFixtures
import io.runwork.bundle.manifest.BundleFile
import io.runwork.bundle.manifest.BundleManifest
import io.runwork.bundle.manifest.FileType
import io.runwork.bundle.manifest.ManifestSigner
import io.runwork.bundle.verification.SignatureVerifier

/**
 * Test bundle with manifest and file contents.
 *
 * This is a fluent builder for creating test bundles with files.
 * It supports both signed (default) and unsigned bundles.
 */
class TestBundle internal constructor(
    val manifest: BundleManifest,
    val files: Map<String, ByteArray>,           // hash -> content
    val fileContents: Map<String, ByteArray>,    // path -> content
    val signer: ManifestSigner?,
    val verifier: SignatureVerifier?,
    /** The public key in Base64 format (for creating BundleConfig) */
    val publicKeyBase64: String?
) {
    companion object {
        /**
         * Create a signed bundle (default).
         */
        fun create(block: TestBundleBuilder.() -> Unit): TestBundle {
            val (privateKeyBase64, publicKeyBase64) = ManifestSigner.generateKeyPair()
            val signer = ManifestSigner.fromBase64(privateKeyBase64)
            val verifier = SignatureVerifier(publicKeyBase64)
            val builder = TestBundleBuilder(signer, verifier, publicKeyBase64)
            builder.block()
            return builder.build()
        }

        /**
         * Create an unsigned bundle.
         */
        fun unsigned(block: TestBundleBuilder.() -> Unit): TestBundle {
            val builder = TestBundleBuilder(null, null, null)
            builder.block()
            return builder.build()
        }
    }

    /**
     * Get file content by path.
     */
    fun contentAt(path: String): ByteArray? = fileContents[path]

    /**
     * Get hash for a file path.
     */
    fun hashFor(path: String): String? {
        return manifest.files.find { it.path == path }?.hash
    }

    /**
     * Create new bundle with updated files, inheriting signer.
     *
     * Files from the original bundle are included unless overwritten.
     */
    fun withUpdatedFiles(block: TestBundleBuilder.() -> Unit): TestBundle {
        val builder = TestBundleBuilder(signer, verifier, publicKeyBase64)

        // Copy existing files from this bundle
        for (file in manifest.files) {
            val content = fileContents[file.path] ?: continue
            builder.file(file.path, content, file.type)
        }

        // Copy metadata
        builder.buildNumber = manifest.buildNumber
        builder.platform = manifest.platform
        builder.mainClass = manifest.mainClass

        // Apply updates
        builder.block()

        return builder.build()
    }

    /**
     * Create new bundle with different build number.
     */
    fun withBuildNumber(buildNumber: Long): TestBundle {
        val newManifest = if (signer != null) {
            signer.signManifest(manifest.copy(buildNumber = buildNumber, signature = ""))
        } else {
            manifest.copy(buildNumber = buildNumber)
        }
        return TestBundle(newManifest, files, fileContents, signer, verifier, publicKeyBase64)
    }
}

/**
 * Builder for creating test bundles.
 */
class TestBundleBuilder(
    private val signer: ManifestSigner?,
    private val verifier: SignatureVerifier?,
    private val publicKeyBase64: String?
) {
    var buildNumber: Long = 1
    var platform: String = "macos-arm64"
    var mainClass: String = "io.runwork.TestMain"

    private val bundleFiles = mutableListOf<BundleFile>()
    private val files = mutableMapOf<String, ByteArray>()        // hash -> content
    private val fileContents = mutableMapOf<String, ByteArray>() // path -> content

    /**
     * Add a file with string content.
     */
    fun file(path: String, content: String, type: FileType = FileType.RESOURCE) {
        file(path, content.toByteArray(), type)
    }

    /**
     * Add a file with byte content.
     */
    fun file(path: String, content: ByteArray, type: FileType = FileType.RESOURCE) {
        val bundleFile = TestFixtures.createBundleFile(path, content, type)

        // Remove existing file at same path if present
        bundleFiles.removeIf { it.path == path }

        bundleFiles.add(bundleFile)
        files[bundleFile.hash] = content
        fileContents[path] = content
    }

    /**
     * Convenience: Add a JAR file.
     */
    fun jar(path: String, content: String) {
        file(path, content.toByteArray(), FileType.JAR)
    }

    /**
     * Convenience: Add a JAR file with byte content.
     */
    fun jar(path: String, content: ByteArray) {
        file(path, content, FileType.JAR)
    }

    /**
     * Convenience: Add a native library.
     */
    fun native(path: String, content: ByteArray) {
        file(path, content, FileType.NATIVE)
    }

    /**
     * Convenience: Add a native library with string content.
     */
    fun native(path: String, content: String) {
        file(path, content.toByteArray(), FileType.NATIVE)
    }

    /**
     * Convenience: Add a resource file.
     */
    fun resource(path: String, content: String) {
        file(path, content.toByteArray(), FileType.RESOURCE)
    }

    /**
     * Convenience: Add a resource file with byte content.
     */
    fun resource(path: String, content: ByteArray) {
        file(path, content, FileType.RESOURCE)
    }

    /**
     * Convenience: Add an executable.
     */
    fun executable(path: String, content: ByteArray) {
        file(path, content, FileType.EXECUTABLE)
    }

    /**
     * Convenience: Add an executable with string content.
     */
    fun executable(path: String, content: String) {
        file(path, content.toByteArray(), FileType.EXECUTABLE)
    }

    /**
     * Add a file with specified size (for testing download strategies).
     *
     * Creates a file filled with 'X' characters.
     */
    fun largeFile(path: String, sizeBytes: Int, type: FileType = FileType.RESOURCE) {
        val content = ByteArray(sizeBytes) { 'X'.code.toByte() }
        file(path, content, type)
    }

    /**
     * Build the test bundle.
     */
    internal fun build(): TestBundle {
        val manifest = if (signer != null) {
            TestFixtures.createSignedManifest(
                files = bundleFiles.toList(),
                signer = signer,
                buildNumber = buildNumber,
                platform = platform,
                mainClass = mainClass
            )
        } else {
            TestFixtures.createTestManifest(
                files = bundleFiles.toList(),
                buildNumber = buildNumber,
                platform = platform,
                mainClass = mainClass
            )
        }

        return TestBundle(
            manifest = manifest,
            files = files.toMap(),
            fileContents = fileContents.toMap(),
            signer = signer,
            verifier = verifier,
            publicKeyBase64 = publicKeyBase64
        )
    }
}
