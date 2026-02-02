package io.runwork.bundle.creator

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages bundle files into distributable formats.
 *
 * Creates:
 * - bundle-{platform}.zip: Per-platform bundle archives for initial downloads
 * - files/: Directory of files named by hash for incremental updates
 */
class BundlePackager {

    /**
     * Package a multi-platform bundle for distribution.
     *
     * @param inputDir Directory containing source files
     * @param outputDir Directory to write output files
     * @param bundleFiles List of BundleFile entries with hashes and platform constraints
     * @param targetPlatforms List of target platform IDs (e.g., ["macos-arm64", "windows-x64"])
     * @return Map of platform ID to bundle zip filename (e.g., "bundle-macos-arm64.zip")
     */
    fun packageBundle(
        inputDir: File,
        outputDir: File,
        bundleFiles: List<BundleFile>,
        targetPlatforms: List<String>,
    ): Map<String, String> {
        outputDir.mkdirs()

        // Create files/ directory with content-addressable names (all files)
        val filesDir = File(outputDir, "files")
        filesDir.mkdirs()

        for (bundleFile in bundleFiles) {
            val sourceFile = File(inputDir, bundleFile.path)
            val hashFileName = bundleFile.hash.removePrefix("sha256:")
            val destFile = File(filesDir, hashFileName)

            if (!destFile.exists()) {
                sourceFile.copyTo(destFile)
            }
        }

        // Create per-platform zip files
        val platformBundleZips = mutableMapOf<String, String>()

        for (platformId in targetPlatforms) {
            val platform = Platform.fromString(platformId)
            val platformFiles = bundleFiles.filter { it.appliesTo(platform) }

            val zipFileName = "bundle-$platformId.zip"
            val bundleZip = File(outputDir, zipFileName)

            val files = platformFiles.map { bf ->
                bf.path to File(inputDir, bf.path)
            }
            createZip(bundleZip, files)

            platformBundleZips[platformId] = zipFileName
        }

        return platformBundleZips
    }

    /**
     * Create a ZIP archive from a list of files.
     *
     * @param output Output ZIP file
     * @param files List of (relativePath, sourceFile) pairs
     */
    fun createZip(output: File, files: List<Pair<String, File>>) {
        ZipOutputStream(output.outputStream()).use { zip ->
            for ((relativePath, file) in files) {
                zip.putNextEntry(ZipEntry(relativePath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
