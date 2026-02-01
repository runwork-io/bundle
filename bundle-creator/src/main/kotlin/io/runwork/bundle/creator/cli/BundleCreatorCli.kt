package io.runwork.bundle.creator.cli

import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.storage.PlatformPaths
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.creator.BundleManifestSigner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * CLI tool for creating signed bundles.
 *
 * Usage:
 *   ./gradlew :bundle-creator:run --args="--input <dir> --output <dir> --platform <platform> --private-key-env <env> [--build-number <num>]"
 *   ./gradlew :bundle-creator:run --args="--input <dir> --output <dir> --platform <platform> --private-key-path <path> [--build-number <num>]"
 *   ./gradlew :bundle-creator:run --args="--generate-keys"
 */
fun main(args: Array<String>) {
    val exitCode = runCli(args)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

/**
 * Run the CLI and return an exit code.
 *
 * @param args Command line arguments
 * @return Exit code (0 for success, non-zero for failure)
 */
fun runCli(args: Array<String>): Int {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        printUsage()
        return 0
    }

    val config = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        printUsage()
        return 1
    }

    return try {
        when (config) {
            is CliConfig.GenerateKeys -> {
                generateKeys()
            }

            is CliConfig.CreateBundle -> {
                runBlocking { BundleCreator(config).create() }
            }
        }
        0 // Success
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        1 // Failure
    }
}

private fun printUsage() {
    println(
        """
        Bundle Creator CLI

        Usage:
          Create bundle:
            --input <dir>           Input directory containing files to bundle
            --output <dir>          Output directory for manifest.json, bundle.zip, and files/
            --platform <platform>   Platform identifier (optional, auto-detected if not specified)
                                    Examples: macos-arm64, macos-x86_64, windows-x86_64, linux-x86_64
            --private-key-env <env> Environment variable containing Base64-encoded private key
            --private-key-path <path> Path to file containing Base64-encoded private key
            --build-number <num>    Optional build number (defaults to current timestamp)
            --main-class <class>    Main class name (defaults to io.runwork.desktop.MainKt)
            --min-shell-version <ver> Minimum shell version required (defaults to 1)
            --root-app-update-url <url> URL where users can download shell updates (optional)

          Generate keys:
            --generate-keys         Generate a new Ed25519 key pair and print to stdout

          Help:
            --help, -h              Show this help message
        """.trimIndent()
    )
}

private sealed class CliConfig {
    data object GenerateKeys : CliConfig()

    data class CreateBundle(
        val inputDir: File,
        val outputDir: File,
        val platform: String,
        val privateKeyBase64: String,
        val buildNumber: Long?,
        val mainClass: String,
        val minShellVersion: Int,
        val rootAppUpdateUrl: String?,
    ) : CliConfig()
}

private fun parseArgs(args: Array<String>): CliConfig {
    if (args.contains("--generate-keys")) {
        return CliConfig.GenerateKeys
    }

    var inputDir: File? = null
    var outputDir: File? = null
    var platform: String? = null
    var privateKeyEnv: String? = null
    var privateKeyPath: String? = null
    var buildNumber: Long? = null
    var mainClass = "io.runwork.desktop.MainKt"
    var minShellVersion = 1
    var rootAppUpdateUrl: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--input" -> {
                inputDir = File(args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for --input"))
            }

            "--output" -> {
                outputDir = File(args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for --output"))
            }

            "--platform" -> {
                platform = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for --platform")
            }

            "--private-key-env" -> {
                privateKeyEnv = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for --private-key-env")
            }

            "--private-key-path" -> {
                privateKeyPath = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for --private-key-path")
            }

            "--build-number" -> {
                buildNumber = args.getOrNull(++i)?.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid value for --build-number")
            }

            "--main-class" -> {
                mainClass = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for --main-class")
            }

            "--min-shell-version" -> {
                minShellVersion = args.getOrNull(++i)?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid value for --min-shell-version")
            }

            "--root-app-update-url" -> {
                rootAppUpdateUrl = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing value for --root-app-update-url")
            }

            else -> {
                throw IllegalArgumentException("Unknown argument: ${args[i]}")
            }
        }
        i++
    }

    if (inputDir == null) throw IllegalArgumentException("--input is required")
    if (outputDir == null) throw IllegalArgumentException("--output is required")
    if (platform == null) platform = PlatformPaths.getPlatform().toString()
    if (privateKeyEnv == null && privateKeyPath == null) {
        throw IllegalArgumentException("Either --private-key-env or --private-key-path is required")
    }

    val privateKeyBase64 = when {
        privateKeyEnv != null -> {
            System.getenv(privateKeyEnv)
                ?: throw IllegalArgumentException("Environment variable $privateKeyEnv not set")
        }

        privateKeyPath != null -> {
            File(privateKeyPath).readText().trim()
        }

        else -> throw IllegalArgumentException("No private key specified")
    }

    return CliConfig.CreateBundle(
        inputDir = inputDir,
        outputDir = outputDir,
        platform = platform,
        privateKeyBase64 = privateKeyBase64,
        buildNumber = buildNumber,
        mainClass = mainClass,
        minShellVersion = minShellVersion,
        rootAppUpdateUrl = rootAppUpdateUrl,
    )
}

private fun generateKeys() {
    val (privateKey, publicKey) = BundleManifestSigner.generateKeyPair()

    println("Ed25519 Key Pair Generated")
    println("==========================")
    println()
    println("Private Key (keep secret!):")
    println(privateKey)
    println()
    println("Public Key (embed in shell):")
    println(publicKey)
    println()
    println("To use:")
    println("  1. Store the private key securely (e.g., in CI secrets)")
    println("  2. Set as environment variable: export RUNWORK_SIGNING_KEY='$privateKey'")
    println("  3. Embed the public key in your shell application's BootstrapConfig")
}

private val prettyJson = Json { prettyPrint = true }

/**
 * Creates signed bundles from an input directory.
 */
private class BundleCreator(private val config: CliConfig.CreateBundle) {

    suspend fun create() {
        // 1. Validate input
        require(config.inputDir.exists()) { "Input directory does not exist: ${config.inputDir}" }

        // 2. Load private key
        val signer = BundleManifestSigner.fromBase64(config.privateKeyBase64)

        // 3. Collect all files and compute hashes
        val files = collectFiles()

        println("Creating bundle:")
        println("  Input: ${config.inputDir.absolutePath}")
        println("  Platform: ${config.platform}")
        println("  Files: ${files.size}")

        // 4. Create output directory structure
        config.outputDir.mkdirs()
        val filesDir = File(config.outputDir, "files")
        filesDir.mkdirs()

        // 5. Copy files to content-addressable names and build manifest entries
        val bundleFiles = mutableListOf<BundleFile>()

        for ((relativePath, file) in files) {
            val hash = HashVerifier.computeHash(file.toPath())
            val hashFileName = hash.removePrefix("sha256:")
            val destFile = File(filesDir, hashFileName)

            if (!destFile.exists()) {
                file.copyTo(destFile)
            }

            bundleFiles.add(
                BundleFile(
                    path = relativePath,
                    hash = hash,
                    size = file.length(),
                )
            )
        }

        // 6. Create bundle.zip
        val bundleZip = File(config.outputDir, "bundle.zip")
        createBundleZip(bundleZip, files)
        val bundleHash = HashVerifier.computeHash(bundleZip.toPath())

        // 7. Create manifest (without signature)
        val buildNumber = config.buildNumber ?: System.currentTimeMillis()
        val manifestWithoutSig = BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            platform = config.platform,
            createdAt = Instant.now().toString(),
            minimumShellVersion = config.minShellVersion,
            rootAppUpdateUrl = config.rootAppUpdateUrl,
            files = bundleFiles,
            mainClass = config.mainClass,
            totalSize = bundleFiles.sumOf { it.size },
            bundleHash = bundleHash,
            signature = ""
        )

        // 8. Sign manifest
        val signedManifest = signer.signManifest(manifestWithoutSig)

        // 9. Write final manifest
        val manifestFile = File(config.outputDir, "manifest.json")
        manifestFile.writeText(prettyJson.encodeToString(signedManifest))

        println()
        println("Bundle created successfully!")
        println("  Build number: $buildNumber")
        println("  Total size: ${signedManifest.totalSize / 1024 / 1024} MB")
        println("  Output: ${config.outputDir.absolutePath}")
        println()
        println("Files created:")
        println("  manifest.json - Signed manifest")
        println("  bundle.zip - Full bundle archive")
        println("  files/ - Individual files by hash (for incremental updates)")
    }

    private fun collectFiles(): List<Pair<String, File>> {
        return config.inputDir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relativePath = file.relativeTo(config.inputDir).path
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
