package io.runwork.bundle.creator

import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.verification.HashVerifier
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages bundle files into distributable formats.
 *
 * Creates:
 * - bundle.zip: Full bundle archive for initial downloads
 * - files/: Directory of files named by hash for incremental updates
 */
class BundlePackager {

    /**
     * Package a bundle for distribution.
     *
     * @param inputDir Directory containing source files
     * @param outputDir Directory to write output files
     * @param bundleFiles List of BundleFile entries with hashes
     * @return Hash of the created bundle.zip
     */
    suspend fun packageBundle(
        inputDir: File,
        outputDir: File,
        bundleFiles: List<BundleFile>,
    ): String {
        outputDir.mkdirs()

        // Create files/ directory with content-addressable names
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

        // Create bundle.zip
        val files = bundleFiles.map { bf ->
            bf.path to File(inputDir, bf.path)
        }
        val bundleZip = File(outputDir, "bundle.zip")
        createZip(bundleZip, files)

        return HashVerifier.computeHash(bundleZip.toPath())
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
