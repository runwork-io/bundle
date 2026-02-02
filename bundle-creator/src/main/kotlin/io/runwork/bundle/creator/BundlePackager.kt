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
 * - bundle-{fingerprint}.zip: Content-addressed bundle archives (platforms with identical content share the same zip)
 * - files/: Directory of files named by hash for incremental updates
 */
class BundlePackager(
    private val manifestBuilder: BundleManifestBuilder = BundleManifestBuilder(),
) {

    /**
     * Package a multi-platform bundle for distribution.
     *
     * Platforms with identical content share the same zip file. Zip files are named
     * using a content fingerprint (hash of the sorted file hashes), ensuring that
     * identical content always produces the same zip filename.
     *
     * @param inputDir Directory containing source files
     * @param outputDir Directory to write output files
     * @param bundleFiles List of BundleFile entries with hashes and platform constraints
     * @param targetPlatforms List of target platform IDs (e.g., ["macos-arm64", "windows-x64"])
     * @return Map of platform ID to bundle zip filename (e.g., "bundle-a1b2c3d4.zip")
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

        // Group platforms by content fingerprint to deduplicate identical zips
        val platformGroups = manifestBuilder.groupPlatformsByContent(bundleFiles, targetPlatforms)
        val platformBundleZips = mutableMapOf<String, String>()

        for ((fingerprint, platforms) in platformGroups) {
            // Content-addressable zip filename
            val zipFileName = "bundle-$fingerprint.zip"
            val bundleZip = File(outputDir, zipFileName)

            // Get files for this content (all platforms in group have same files)
            val platform = Platform.fromString(platforms.first())
            val platformFiles = bundleFiles.filter { it.appliesTo(platform) }

            // Create the zip once
            val files = platformFiles.map { bf ->
                bf.path to File(inputDir, bf.path)
            }
            createZip(bundleZip, files)

            // Point all platforms in the group to this zip
            for (platformId in platforms) {
                platformBundleZips[platformId] = zipFileName
            }
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
