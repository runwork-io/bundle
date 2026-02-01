package io.runwork.bundle.gradle

import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.storage.PlatformPaths
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.creator.BundleManifestSigner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
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
 * Gradle task for creating signed bundles.
 *
 * This task packages a directory into a signed bundle with:
 * - manifest.json: Signed manifest file
 * - bundle.zip: Full bundle archive for initial downloads
 * - files/: Individual files named by hash for incremental updates
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
 *
 *     // Preferred: use Gradle's environment variable provider
 *     privateKey.set(providers.environmentVariable("BUNDLE_PRIVATE_KEY"))
 *
 *     // Alternative: specify env var name (task reads it at execution time)
 *     // privateKeyEnvVar.set("BUNDLE_PRIVATE_KEY")
 *
 *     // Alternative: read from file
 *     // privateKeyFile.set(file("path/to/private.key"))
 *
 *     dependsOn("installDist")
 * }
 * ```
 */
abstract class BundleCreatorTask : DefaultTask() {

    /**
     * Directory containing files to bundle.
     */
    @get:InputDirectory
    abstract val inputDirectory: DirectoryProperty

    /**
     * Output directory for manifest.json, bundle.zip, and files/.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * Platform identifier (e.g., "macos-arm64", "windows-x86_64").
     * Defaults to auto-detect from current system.
     */
    @get:Input
    @get:Optional
    abstract val platform: Property<String>

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

        // Determine platform
        val platformId = if (platform.isPresent) {
            platform.get()
        } else {
            PlatformPaths.getPlatform().toString()
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

        // Collect files
        val files = collectFiles(inputDir)

        logger.lifecycle("Creating bundle:")
        logger.lifecycle("  Input: ${inputDir.absolutePath}")
        logger.lifecycle("  Platform: $platformId")
        logger.lifecycle("  Files: ${files.size}")

        // Create output directories
        outputDir.mkdirs()
        val filesDir = File(outputDir, "files")
        filesDir.mkdirs()

        // Copy files and build manifest entries
        val bundleFiles = runBlocking {
            files.map { (relativePath, file) ->
                val hash = HashVerifier.computeHash(file.toPath())
                val hashFileName = hash.removePrefix("sha256:")
                val destFile = File(filesDir, hashFileName)

                if (!destFile.exists()) {
                    file.copyTo(destFile)
                }

                BundleFile(
                    path = relativePath,
                    hash = hash,
                    size = file.length(),
                )
            }
        }

        // Create bundle.zip
        val bundleZip = File(outputDir, "bundle.zip")
        createBundleZip(bundleZip, files)
        val bundleHash = runBlocking { HashVerifier.computeHash(bundleZip.toPath()) }

        // Create unsigned manifest
        val manifestWithoutSig = BundleManifest(
            schemaVersion = 1,
            buildNumber = build,
            platform = platformId,
            createdAt = Instant.now().toString(),
            minimumShellVersion = minShell,
            shellUpdateUrl = updateUrl,
            files = bundleFiles,
            mainClass = mainClass.get(),
            totalSize = bundleFiles.sumOf { it.size },
            bundleHash = bundleHash,
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
        logger.lifecycle("  Total size: ${signedManifest.totalSize / 1024 / 1024} MB")
        logger.lifecycle("  Output: ${outputDir.absolutePath}")
        logger.lifecycle("")
        logger.lifecycle("Files created:")
        logger.lifecycle("  manifest.json - Signed manifest")
        logger.lifecycle("  bundle.zip - Full bundle archive")
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

    private fun collectFiles(inputDir: File): List<Pair<String, File>> {
        return inputDir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relativePath = file.relativeTo(inputDir).path
                    .replace(File.separatorChar, '/') // Normalize to forward slashes
                relativePath to file
            }
            .toList()
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
