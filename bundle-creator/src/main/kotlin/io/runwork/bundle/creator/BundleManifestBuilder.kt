package io.runwork.bundle.creator

import io.runwork.bundle.common.Arch
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.common.verification.HashVerifier
import java.io.File
import java.time.Instant

/**
 * Builds a BundleManifest from a directory of files.
 *
 * Supports multi-platform bundles with platform-specific resources folder structure:
 * ```
 * inputDir/
 * ├── lib/                   # Universal files
 * └── resources/
 *     ├── common/            # Universal (os=null, arch=null)
 *     ├── macos/             # macOS only (os=MACOS, arch=null)
 *     ├── macos-arm64/       # macOS ARM64 (os=MACOS, arch=ARM64)
 *     └── ...
 * ```
 */
class BundleManifestBuilder {

    /**
     * Build a multi-platform manifest from an input directory.
     *
     * @param inputDir Directory containing files to bundle
     * @param targetPlatforms List of target platform IDs (e.g., ["macos-arm64", "windows-x64"])
     * @param buildNumber Build number for the manifest
     * @param mainClass Fully qualified main class name
     * @param minShellVersion Minimum shell version required
     * @param zips Map of platform ID to PlatformBundle (from BundlePackager output)
     * @param shellUpdateUrl Optional URL for shell updates
     * @return Unsigned manifest (signature field is empty)
     */
    fun build(
        inputDir: File,
        targetPlatforms: List<String>,
        buildNumber: Long,
        mainClass: String,
        minShellVersion: Int,
        zips: Map<String, PlatformBundle>,
        shellUpdateUrl: String? = null,
    ): BundleManifest {
        val bundleFiles = collectFilesWithPlatformConstraints(inputDir)

        return BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            createdAt = Instant.now().toString(),
            minShellVersion = minShellVersion,
            shellUpdateUrl = shellUpdateUrl,
            files = bundleFiles,
            mainClass = mainClass,
            zips = zips,
            signature = "",
        )
    }

    /**
     * Collect all files from a directory with their relative paths and platform constraints.
     *
     * Files are classified based on their location:
     * - Outside resources/: Universal (os=null, arch=null)
     * - resources/common/: Universal (os=null, arch=null)
     * - resources/{os}/: OS-specific (arch=null)
     * - resources/{os}-{arch}/: Platform-specific
     *
     * @param inputDir Directory to scan
     * @return List of BundleFile entries with platform constraints
     */
    fun collectFilesWithPlatformConstraints(inputDir: File): List<BundleFile> {
        val files = inputDir.walkTopDown()
            .filter { it.isFile }
            .toList()

        val result = mutableListOf<BundleFile>()
        for (file in files) {
            val relativePath = file.relativeTo(inputDir).path
                .replace(File.separatorChar, '/') // Normalize to forward slashes

            val (os, arch) = detectPlatformConstraints(relativePath)

            result.add(
                BundleFile(
                    path = relativePath,
                    hash = HashVerifier.computeHashSync(file.toPath()),
                    size = file.length(),
                    os = os,
                    arch = arch,
                )
            )
        }
        return result
    }

    /**
     * Detect platform constraints from file path based on resources folder structure.
     *
     * @param relativePath Path relative to input directory
     * @return Pair of (Os?, Arch?) constraints
     */
    private fun detectPlatformConstraints(relativePath: String): Pair<Os?, Arch?> {
        // Check if path starts with resources/
        if (!relativePath.startsWith("resources/")) {
            return null to null // Universal file
        }

        // Extract the platform folder name (first segment after resources/)
        val pathAfterResources = relativePath.removePrefix("resources/")
        val platformFolder = pathAfterResources.substringBefore('/')

        // Handle common/ folder
        if (platformFolder == "common") {
            return null to null // Universal file
        }

        // Try to parse as platform (os-arch)
        if (platformFolder.contains('-')) {
            val parts = platformFolder.split('-')
            if (parts.size == 2) {
                val os = Os.entries.find { it.id == parts[0] }
                val arch = try {
                    Arch.fromId(parts[1])
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (os != null && arch != null) {
                    return os to arch
                }
            }
        }

        // Try to parse as OS only (e.g., "macos", "windows", "linux")
        val os = Os.entries.find { it.id == platformFolder }
        if (os != null) {
            return os to null
        }

        // Unknown folder - treat as universal
        return null to null
    }

    /**
     * Detect target platforms from the resources directory structure.
     *
     * Scans resources/ subdirectories for platform-specific folders:
     * - Full platform IDs (e.g., "macos-arm64") are detected directly
     * - OS-only folders (e.g., "macos") are expanded to all architectures for that OS
     *
     * @param inputDir Directory to scan
     * @return List of platform IDs found (e.g., ["macos-arm64", "macos-x64", "windows-x64"])
     */
    fun detectPlatforms(inputDir: File): List<String> {
        val resourcesDir = File(inputDir, "resources")
        if (!resourcesDir.exists() || !resourcesDir.isDirectory) {
            return listOf()
        }

        val platforms = mutableSetOf<String>()

        resourcesDir.listFiles()
            ?.filter { it.isDirectory && it.name != "common" }
            ?.forEach { folder ->
                val name = folder.name

                // Check if it's a full platform ID (os-arch)
                if (name.contains('-')) {
                    val parts = name.split('-')
                    if (parts.size == 2) {
                        val os = Os.entries.find { it.id == parts[0] }
                        val arch = try {
                            Arch.fromId(parts[1])
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                        if (os != null && arch != null) {
                            platforms.add("${os.id}-${arch.id}")
                        }
                    }
                } else {
                    // Check if it's an OS-only folder - expand to all architectures
                    val os = Os.entries.find { it.id == name }
                    if (os != null) {
                        for (arch in Arch.entries) {
                            platforms.add("${os.id}-${arch.id}")
                        }
                    }
                }
            }

        return platforms.sorted()
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

    /**
     * Compute a content fingerprint for a set of files.
     * Uses SHA-256 of sorted file hashes to create a unique identifier.
     *
     * @param files List of BundleFile entries
     * @return Short hash prefix (first 8 chars) for use in zip filename
     */
    fun computeContentFingerprint(files: List<BundleFile>): String {
        val sortedHashes = files.map { it.hash }.sorted().joinToString(",")
        val fullHash = HashVerifier.computeHash(sortedHashes.toByteArray())
        // Return first 8 chars of hash (without "sha256:" prefix) for filename
        return fullHash.removePrefix("sha256:").take(8)
    }

    /**
     * Group platforms by their content fingerprint.
     * Platforms with identical file sets share the same fingerprint.
     *
     * @param bundleFiles List of all BundleFile entries
     * @param targetPlatforms List of target platform IDs
     * @return Map of fingerprint to list of platform IDs
     */
    fun groupPlatformsByContent(
        bundleFiles: List<BundleFile>,
        targetPlatforms: List<String>,
    ): Map<String, List<String>> {
        return targetPlatforms.groupBy { platformId ->
            val platform = Platform.fromString(platformId)
            val platformFiles = bundleFiles.filter { it.appliesTo(platform) }
            computeContentFingerprint(platformFiles)
        }
    }
}
