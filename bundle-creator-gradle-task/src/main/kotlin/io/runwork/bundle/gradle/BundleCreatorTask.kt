package io.runwork.bundle.gradle

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.creator.BundleManifestBuilder
import io.runwork.bundle.creator.BundleManifestSigner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gradle task for creating signed multi-platform bundles.
 *
 * This task packages a directory into a signed bundle with:
 * - manifest.json: Signed manifest file with multi-platform support
 * - bundle-{fingerprint}.zip: Per-platform bundle archives for initial downloads (deduplicated by content)
 * - files/: Individual files named by hash for incremental updates
 *
 * Target platforms must be explicitly specified via the `platforms` property.
 *
 * Platform-specific resources can be organized in the resources/ folder structure:
 * - resources/common/ - Universal files (included for all platforms)
 * - resources/macos/ - macOS only (both arm64 and x64)
 * - resources/macos-arm64/ - macOS ARM64 only
 * - resources/windows-x64/ - Windows x64 only
 * - etc.
 *
 * Usage:
 * ```kotlin
 * import io.runwork.bundle.gradle.BundleCreatorTask
 *
 * tasks.register<BundleCreatorTask>("createBundle") {
 *     inputDirectory.set(layout.buildDirectory.dir("install/myapp"))
 *     outputDirectory.set(layout.buildDirectory.dir("bundle"))
 *     mainClass.set("com.myapp.MainKt")
 *     buildNumber.set(System.currentTimeMillis())  // Or use CI build number
 *     platforms.set(listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64"))
 *
 *     // Preferred: use Gradle's environment variable provider
 *     privateKey.set(providers.environmentVariable("BUNDLE_PRIVATE_KEY"))
 *
 *     dependsOn("installDist")
 * }
 * ```
 */
abstract class BundleCreatorTask : DefaultTask() {

    private val manifestBuilder = BundleManifestBuilder()

    /**
     * Directory containing files to bundle.
     */
    @get:InputDirectory
    abstract val inputDirectory: DirectoryProperty

    /**
     * Output directory for manifest.json, bundle-{platform}.zip files, and files/.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * List of target platforms (e.g., ["macos-arm64", "macos-x64", "windows-x64"]).
     *
     * Valid platform values are: macos-arm64, macos-x64, windows-arm64, windows-x64, linux-arm64, linux-x64
     */
    @get:Input
    abstract val platforms: ListProperty<String>

    /**
     * Build number for the manifest.
     * This is required and should be set by your CI system.
     */
    @get:Input
    abstract val buildNumber: Property<Long>

    /**
     * Fully qualified main class name.
     */
    @get:Input
    abstract val mainClass: Property<String>

    /**
     * Minimum shell version required to run this bundle.
     * Defaults to 1.
     */
    @get:Input
    @get:Optional
    abstract val minShellVersion: Property<Int>

    /**
     * URL where users can download shell updates.
     */
    @get:Input
    @get:Optional
    abstract val shellUpdateUrl: Property<String>

    /**
     * Base64-encoded private key value.
     * This is the preferred option for CI/CD as it works well with Gradle providers:
     * ```kotlin
     * privateKey.set(providers.environmentVariable("BUNDLE_PRIVATE_KEY"))
     * ```
     * One of privateKey, privateKeyEnvVar, or privateKeyFile must be set.
     */
    @get:Input
    @get:Optional
    abstract val privateKey: Property<String>

    /**
     * Environment variable name containing the Base64-encoded private key.
     * The task will read from this environment variable at execution time.
     * One of privateKey, privateKeyEnvVar, or privateKeyFile must be set.
     */
    @get:Input
    @get:Optional
    abstract val privateKeyEnvVar: Property<String>

    /**
     * File containing the Base64-encoded private key.
     * One of privateKey, privateKeyEnvVar, or privateKeyFile must be set.
     */
    @get:Optional
    @get:org.gradle.api.tasks.InputFile
    abstract val privateKeyFile: RegularFileProperty

    companion object {
        private val prettyJson = Json { prettyPrint = true }

        /**
         * Generate a new Ed25519 key pair.
         *
         * @return Pair of (privateKeyBase64, publicKeyBase64)
         */
        @JvmStatic
        fun generateKeyPair(): Pair<String, String> {
            return BundleManifestSigner.generateKeyPair()
        }
    }

    @TaskAction
    fun createBundle() {
        val inputDir = inputDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile

        // Validate input
        if (!inputDir.exists()) {
            throw GradleException("Input directory does not exist: ${inputDir.absolutePath}")
        }

        // Get private key
        val privateKeyBase64 = resolvePrivateKey()

        // Get and validate target platforms
        val targetPlatforms = platforms.get()
        if (targetPlatforms.isEmpty()) {
            throw GradleException("The 'platforms' property must not be empty")
        }

        // Validate each platform
        for (platformId in targetPlatforms) {
            try {
                Platform.fromString(platformId)
            } catch (e: IllegalArgumentException) {
                throw GradleException(
                    "Invalid platform '$platformId'. Valid platforms are: " +
                        "macos-arm64, macos-x64, windows-arm64, windows-x64, linux-arm64, linux-x64"
                )
            }
        }

        // Get build number (required)
        val build = buildNumber.get()

        // Get min shell version
        val minShell = if (minShellVersion.isPresent) {
            minShellVersion.get()
        } else {
            1
        }

        // Get shell update URL
        val updateUrl = if (shellUpdateUrl.isPresent) {
            shellUpdateUrl.get()
        } else {
            null
        }

        // Load signer
        val signer = BundleManifestSigner.fromBase64(privateKeyBase64)

        // Collect files with platform constraints
        val bundleFiles = manifestBuilder.collectFilesWithPlatformConstraints(inputDir)

        logger.lifecycle("Creating multi-platform bundle:")
        logger.lifecycle("  Input: ${inputDir.absolutePath}")
        logger.lifecycle("  Platforms: ${targetPlatforms.joinToString(", ")}")
        logger.lifecycle("  Total files: ${bundleFiles.size}")

        // Create output directories
        outputDir.mkdirs()
        val filesDir = File(outputDir, "files")
        filesDir.mkdirs()

        // Copy all files to files/ directory (content-addressable)
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
            createBundleZip(bundleZip, files)

            // Point all platforms in the group to this zip
            for (platformId in platforms) {
                platformBundleZips[platformId] = zipFileName
            }

            logger.lifecycle("  Created $zipFileName (${platformFiles.size} files) for: ${platforms.joinToString(", ")}")
        }

        // Build platform bundles map with total sizes
        val platformBundlesMap = targetPlatforms.associateWith { platformId ->
            val platform = Platform.fromString(platformId)
            val platformFiles = bundleFiles.filter { it.appliesTo(platform) }
            val totalSize = platformFiles.sumOf { it.size }
            PlatformBundle(
                bundleZip = platformBundleZips[platformId]!!,
                totalSize = totalSize,
            )
        }

        // Create unsigned manifest
        val manifestWithoutSig = BundleManifest(
            schemaVersion = 1,
            buildNumber = build,
            createdAt = Instant.now().toString(),
            minShellVersion = minShell,
            shellUpdateUrl = updateUrl,
            files = bundleFiles,
            mainClass = mainClass.get(),
            platformBundles = platformBundlesMap,
            signature = ""
        )

        // Sign manifest
        val signedManifest = signer.signManifest(manifestWithoutSig)

        // Write manifest
        val manifestFile = File(outputDir, "manifest.json")
        manifestFile.writeText(prettyJson.encodeToString(signedManifest))

        logger.lifecycle("")
        logger.lifecycle("Bundle created successfully!")
        logger.lifecycle("  Build number: $build")
        logger.lifecycle("  Output: ${outputDir.absolutePath}")
        logger.lifecycle("")
        logger.lifecycle("Files created:")
        logger.lifecycle("  manifest.json - Signed multi-platform manifest")
        // List unique zip files
        val uniqueZips = platformBundleZips.entries.groupBy({ it.value }, { it.key })
        for ((zipName, platformsForZip) in uniqueZips) {
            val totalSize = platformBundlesMap[platformsForZip.first()]!!.totalSize
            val sizeMb = totalSize / 1024 / 1024
            logger.lifecycle("  $zipName - $sizeMb MB (${platformsForZip.joinToString(", ")})")
        }
        logger.lifecycle("  files/ - Individual files by hash (for incremental updates)")
    }

    private fun resolvePrivateKey(): String {
        return when {
            privateKey.isPresent -> {
                privateKey.get()
            }

            privateKeyEnvVar.isPresent -> {
                val envVar = privateKeyEnvVar.get()
                System.getenv(envVar)
                    ?: throw GradleException("Environment variable $envVar not set")
            }

            privateKeyFile.isPresent -> {
                privateKeyFile.get().asFile.readText().trim()
            }

            else -> {
                throw GradleException("One of privateKey, privateKeyEnvVar, or privateKeyFile must be set")
            }
        }
    }

    private fun createBundleZip(output: File, files: List<Pair<String, File>>) {
        ZipOutputStream(output.outputStream()).use { zip ->
            for ((relativePath, file) in files) {
                zip.putNextEntry(ZipEntry(relativePath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
