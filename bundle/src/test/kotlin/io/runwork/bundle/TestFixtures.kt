package io.runwork.bundle

import io.runwork.bundle.manifest.BundleFile
import io.runwork.bundle.manifest.BundleManifest
import io.runwork.bundle.manifest.FileType
import io.runwork.bundle.manifest.ManifestSigner
import io.runwork.bundle.verification.HashVerifier
import io.runwork.bundle.verification.SignatureVerifier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
     * Compute SHA-256 hash of content using Okio.
     */
    fun computeHash(content: ByteArray): String {
        return "sha256:" + content.toByteString().sha256().hex()
    }

    /**
     * Compute SHA-256 hash of a file using Okio (streaming, memory-efficient).
     */
    suspend fun computeHash(path: Path): String {
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
     *   bundle.zip              # Full bundle ZIP (optional)
     *   files/
     *     <hash1>               # Individual files by hash (no sha256: prefix)
     *     <hash2>
     * ```
     *
     * @param baseDir The directory to create the bundle server in
     * @param manifest The bundle manifest to write
     * @param files Map of hash (with sha256: prefix) to file contents
     * @param includeZip If true, also creates bundle.zip with all files
     * @return file:// URL pointing to the bundle server
     */
    fun createFileBundleServer(
        baseDir: Path,
        manifest: BundleManifest,
        files: Map<String, ByteArray>,
        includeZip: Boolean = false
    ): String {
        // Create directories
        val filesDir = baseDir.resolve("files")
        Files.createDirectories(filesDir)

        // Write manifest.json
        val manifestPath = baseDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Write individual files by hash (without sha256: prefix)
        for ((hash, content) in files) {
            val hashWithoutPrefix = hash.removePrefix("sha256:")
            val filePath = filesDir.resolve(hashWithoutPrefix)
            Files.write(filePath, content)
        }

        // Optionally create bundle.zip
        if (includeZip) {
            val zipPath = baseDir.resolve("bundle.zip")
            createBundleZip(zipPath, manifest, files)
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
        files: Map<String, ByteArray>
    ) {
        // Stream directly to the ZIP file instead of buffering in memory
        Files.newOutputStream(zipPath).use { fos ->
            ZipOutputStream(fos).use { zos ->
                for (bundleFile in manifest.files) {
                    val content = files[bundleFile.hash]
                    if (content != null) {
                        zos.putNextEntry(ZipEntry(bundleFile.path))
                        zos.write(content)
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Content provider for file data - allows both in-memory and file-backed content.
     *
     * This interface enables memory-efficient handling of large files by allowing
     * content to be streamed from disk rather than held entirely in memory.
     */
    sealed interface FileContent {
        /** In-memory byte array content */
        data class Bytes(val data: ByteArray) : FileContent {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Bytes) return false
                return data.contentEquals(other.data)
            }

            override fun hashCode(): Int = data.contentHashCode()
        }

        /** File-backed content (memory-efficient for large files) */
        data class File(val path: Path) : FileContent
    }

    /**
     * Create a file-based bundle server with file-backed content (memory-efficient).
     *
     * This overload accepts FileContent which can be either in-memory bytes or
     * file paths, allowing large files to be streamed without loading into memory.
     */
    fun createFileBundleServerStreaming(
        baseDir: Path,
        manifest: BundleManifest,
        files: Map<String, FileContent>,
        includeZip: Boolean = false
    ): String {
        val filesDir = baseDir.resolve("files")
        Files.createDirectories(filesDir)

        // Write manifest.json
        val manifestPath = baseDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        // Write individual files by hash (without sha256: prefix)
        for ((hash, content) in files) {
            val hashWithoutPrefix = hash.removePrefix("sha256:")
            val filePath = filesDir.resolve(hashWithoutPrefix)
            when (content) {
                is FileContent.Bytes -> Files.write(filePath, content.data)
                is FileContent.File -> Files.copy(content.path, filePath)
            }
        }

        // Optionally create bundle.zip
        if (includeZip) {
            val zipPath = baseDir.resolve("bundle.zip")
            createBundleZipStreaming(zipPath, manifest, files)
        }

        return baseDir.toUri().toString().trimEnd('/')
    }

    /**
     * Create a bundle.zip file with streaming support for file-backed content.
     */
    private fun createBundleZipStreaming(
        zipPath: Path,
        manifest: BundleManifest,
        files: Map<String, FileContent>
    ) {
        Files.newOutputStream(zipPath).use { fos ->
            ZipOutputStream(fos).use { zos ->
                for (bundleFile in manifest.files) {
                    val content = files[bundleFile.hash] ?: continue

                    zos.putNextEntry(ZipEntry(bundleFile.path))
                    when (content) {
                        is FileContent.Bytes -> zos.write(content.data)
                        is FileContent.File -> {
                            // Stream file content directly from file
                            Files.newInputStream(content.path).use { input ->
                                input.copyTo(zos)
                            }
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }
}
