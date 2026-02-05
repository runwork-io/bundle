package samples

import io.runwork.bundle.creator.BundlePackager
import io.runwork.bundle.creator.BundleManifestBuilder
import io.runwork.bundle.creator.BundleManifestSigner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Use Case 3: Bundle Creator (CI Job)
 *
 * Creates and signs multi-platform bundles for distribution. This code runs
 * in CI/CD pipelines, not in the shell or bundle.
 *
 * The input directory should have platform-specific files in resources/ subdirectories:
 * ```
 * inputDir/
 * ├── lib/                   # Universal files (JARs, etc.)
 * └── resources/
 *     ├── common/            # Universal resources
 *     ├── macos-arm64/       # macOS ARM64-specific
 *     ├── macos-x64/         # macOS x64-specific
 *     ├── windows-x64/       # Windows x64-specific
 *     └── linux-x64/         # Linux x64-specific
 * ```
 */
suspend fun createBundle() {
    val inputDir = File("build/distributions/myapp")
    val outputDir = File("build/bundle-output")

    // Load private key from environment (CI secret)
    val privateKeyBase64 = System.getenv("BUNDLE_PRIVATE_KEY")
        ?: error("BUNDLE_PRIVATE_KEY environment variable not set")

    val signer = BundleManifestSigner.fromBase64(privateKeyBase64)
    val builder = BundleManifestBuilder()
    val packager = BundlePackager()

    // Step 1: Specify target platforms explicitly
    val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")
    println("Target platforms: $targetPlatforms")

    // Step 2: Collect files with platform constraints and compute hashes
    val bundleFiles = builder.collectFilesWithPlatformConstraints(inputDir)
    println("Collected ${bundleFiles.size} files")

    // Step 3: Package bundles (creates per-platform bundle-{platform}.zip and files/ directory)
    // Returns a map of platform ID to PlatformBundle (with zip filename and actual zip size)
    val platformBundles = packager.packageBundle(inputDir, outputDir, bundleFiles, targetPlatforms)
    println("Bundles packaged:")
    platformBundles.forEach { (platform, bundle) ->
        println("  $platform -> ${bundle.bundleZip} (${bundle.size} bytes)")
    }

    // Step 4: Build unsigned manifest
    val unsignedManifest = builder.build(
        inputDir = inputDir,
        targetPlatforms = targetPlatforms,
        buildNumber = System.currentTimeMillis(), // Or from CI build number
        mainClass = "com.myapp.MainKt",
        minShellVersion = 1,
        platformBundles = platformBundles,
        shellUpdateUrl = null, // Optional: URL to update the shell app itself
    )

    // Step 5: Sign manifest with Ed25519
    val signedManifest = signer.signManifest(unsignedManifest)

    // Step 6: Write manifest.json to output directory
    val json = Json { prettyPrint = true }
    val manifestJson = json.encodeToString(signedManifest)
    File(outputDir, "manifest.json").writeText(manifestJson)

    println("Bundle created:")
    println("  Build: ${signedManifest.buildNumber}")
    println("  Platforms: ${signedManifest.platformBundles.keys.joinToString(", ")}")
    println("  Files: ${signedManifest.files.size}")
    signedManifest.platformBundles.forEach { (platform, bundle) ->
        println("  $platform: ${bundle.size} bytes (${bundle.bundleZip})")
    }
    println("  Output: $outputDir")
}

/**
 * Generate a new Ed25519 key pair.
 * Run once, store private key securely in CI secrets.
 */
fun generateKeys() {
    val (privateKey, publicKey) = BundleManifestSigner.generateKeyPair()
    println("Private key (store in CI secrets): $privateKey")
    println("Public key (embed in shell app): $publicKey")
}

/**
 * Output Structure after running createBundle():
 *
 * build/bundle-output/
 * ├── manifest.json              # Signed manifest (JSON) - single manifest for all platforms
 * ├── bundle-macos-arm64.zip     # macOS ARM64 bundle archive
 * ├── bundle-macos-x64.zip       # macOS x64 bundle archive
 * ├── bundle-windows-x64.zip     # Windows x64 bundle archive
 * ├── bundle-linux-x64.zip       # Linux x64 bundle archive
 * └── files/
 *     ├── abc123...              # File contents by SHA-256 hash (all platforms)
 *     ├── def456...
 *     └── ...
 *
 * The manifest.json contains platform-specific bundle information:
 * - platformBundles: Map of platform ID to {bundleZip, size}
 * - files: List of all files with optional os/arch constraints
 *
 * Example manifest structure:
 * {
 *   "platformBundles": {
 *     "macos-arm64": { "bundleZip": "bundle-macos-arm64.zip", "size": 12345678 },
 *     "windows-x64": { "bundleZip": "bundle-windows-x64.zip", "size": 12345678 }
 *   },
 *   "files": [
 *     { "path": "lib/app.jar", "hash": "sha256:...", "size": 1234 },
 *     { "path": "resources/macos-arm64/native.dylib", "hash": "sha256:...", "size": 5678, "os": "macos", "arch": "arm64" }
 *   ]
 * }
 */
