package io.runwork.bundle.testing

import io.runwork.bundle.BundleConfig
import io.runwork.bundle.TestFixtures
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-based bundle server for integration testing.
 *
 * Manages a file-based bundle server directory structure that can be
 * accessed via file:// URLs. This eliminates the need for MockWebServer
 * in integration tests.
 *
 * Directory structure:
 * ```
 * baseDir/
 *   manifest.json           # Bundle manifest JSON
 *   bundle.zip              # Full bundle ZIP (optional)
 *   files/
 *     <hash1>               # Individual files by hash (no sha256: prefix)
 *     <hash2>
 * ```
 */
class TestBundleServer private constructor(
    val baseDir: Path,
    val baseUrl: String
) : AutoCloseable {

    companion object {
        /**
         * Create a server in the given directory.
         */
        fun create(baseDir: Path): TestBundleServer {
            Files.createDirectories(baseDir)
            val baseUrl = baseDir.toUri().toString().trimEnd('/')
            return TestBundleServer(baseDir, baseUrl)
        }
    }

    private val filesDir: Path = baseDir.resolve("files")
    private val manifestPath: Path = baseDir.resolve("manifest.json")
    private val zipPath: Path = baseDir.resolve("bundle.zip")

    /**
     * Publish a bundle to this server.
     *
     * @param bundle The test bundle to publish
     * @param includeZip If true, also creates bundle.zip for full bundle downloads
     */
    fun publish(bundle: TestBundle, includeZip: Boolean = false) {
        TestFixtures.createFileBundleServer(
            baseDir = baseDir,
            manifest = bundle.manifest,
            files = bundle.files,
            includeZip = includeZip
        )
    }

    /**
     * Create a BundleConfig pointing to this server.
     *
     * @param publicKey Ed25519 public key for verification (base64 encoded)
     * @param appDataDir Application data directory for bundle storage
     * @param platform Platform identifier (defaults to "macos-arm64")
     */
    fun bundleConfig(
        publicKey: String,
        appDataDir: Path,
        platform: String = "macos-arm64"
    ): BundleConfig {
        return BundleConfig(
            baseUrl = baseUrl,
            publicKey = publicKey,
            appDataDir = appDataDir,
            platform = platform
        )
    }

    /**
     * Remove a file from the server (for error testing).
     *
     * @param hash The file hash (with or without sha256: prefix)
     */
    fun removeFile(hash: String) {
        val hashWithoutPrefix = hash.removePrefix("sha256:")
        val filePath = filesDir.resolve(hashWithoutPrefix)
        Files.deleteIfExists(filePath)
    }

    /**
     * Corrupt a file on the server (for hash mismatch testing).
     *
     * Writes garbage data to the file while preserving its path.
     *
     * @param hash The file hash (with or without sha256: prefix)
     */
    fun corruptFile(hash: String) {
        val hashWithoutPrefix = hash.removePrefix("sha256:")
        val filePath = filesDir.resolve(hashWithoutPrefix)
        if (Files.exists(filePath)) {
            Files.writeString(filePath, "CORRUPTED DATA - INVALID CONTENT")
        }
    }

    /**
     * Remove the manifest (for missing manifest testing).
     */
    fun removeManifest() {
        Files.deleteIfExists(manifestPath)
    }

    /**
     * Remove the bundle.zip (for missing zip testing).
     */
    fun removeZip() {
        Files.deleteIfExists(zipPath)
    }

    /**
     * Cleanup the server directory.
     */
    override fun close() {
        TestFixtures.deleteRecursively(baseDir)
    }
}
