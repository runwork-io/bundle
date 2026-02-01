package io.runwork.bundle.creator

import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.verification.HashVerifier
import java.io.File
import java.time.Instant

/**
 * Builds a BundleManifest from a directory of files.
 */
class BundleManifestBuilder {

    /**
     * Build a manifest from an input directory.
     *
     * @param inputDir Directory containing files to bundle
     * @param platform Platform identifier (e.g., "macos-arm64")
     * @param buildNumber Build number for the manifest
     * @param mainClass Fully qualified main class name
     * @param minShellVersion Minimum shell version required
     * @param bundleHash SHA-256 hash of the bundle.zip file
     * @param rootAppUpdateUrl Optional URL for shell updates
     * @return Unsigned manifest (signature field is empty)
     */
    suspend fun build(
        inputDir: File,
        platform: String,
        buildNumber: Long,
        mainClass: String,
        minShellVersion: Int,
        bundleHash: String,
        rootAppUpdateUrl: String? = null,
    ): BundleManifest {
        val files = collectFiles(inputDir)
        val bundleFiles = files.map { (relativePath, file) ->
            BundleFile(
                path = relativePath,
                hash = HashVerifier.computeHash(file.toPath()),
                size = file.length(),
            )
        }

        return BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            platform = platform,
            createdAt = Instant.now().toString(),
            minimumShellVersion = minShellVersion,
            rootAppUpdateUrl = rootAppUpdateUrl,
            files = bundleFiles,
            mainClass = mainClass,
            totalSize = bundleFiles.sumOf { it.size },
            bundleHash = bundleHash,
            signature = "",
        )
    }

    /**
     * Collect all files from a directory with their relative paths.
     *
     * @param inputDir Directory to scan
     * @return List of (relativePath, file) pairs
     */
    fun collectFiles(inputDir: File): List<Pair<String, File>> {
        return inputDir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relativePath = file.relativeTo(inputDir).path
                    .replace(File.separatorChar, '/') // Normalize to forward slashes
                relativePath to file
            }
            .toList()
    }

}
