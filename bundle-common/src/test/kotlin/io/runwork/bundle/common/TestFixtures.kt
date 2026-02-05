package io.runwork.bundle.common

import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.common.verification.HashVerifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test utilities for bundle-common tests.
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
     * Create a BundleFile with computed hash.
     */
    fun createBundleFile(
        path: String,
        content: ByteArray,
        os: Os? = null,
        arch: Arch? = null,
    ): BundleFile {
        val hash = computeHash(content)
        return BundleFile(
            path = path,
            hash = hash,
            size = content.size.toLong(),
            os = os,
            arch = arch,
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
}
