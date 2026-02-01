package samples

import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.creator.BundlePackager
import io.runwork.bundle.creator.BundleManifestBuilder
import io.runwork.bundle.creator.BundleManifestSigner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Use Case 3: Bundle Creator (CI Job)
 *
 * Creates and signs bundles for distribution. This code runs
 * in CI/CD pipelines, not in the shell or bundle.
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

    // Step 1: Collect files and compute hashes
    val collectedFiles = builder.collectFiles(inputDir)
    println("Collected ${collectedFiles.size} files")

    val bundleFiles = collectedFiles.map { (relativePath, file) ->
        BundleFile(
            path = relativePath,
            hash = HashVerifier.computeHash(file.toPath()),
            size = file.length(),
        )
    }

    // Step 2: Package bundle (creates bundle.zip and files/ directory)
    // Returns the SHA-256 hash of bundle.zip
    val bundleHash = packager.packageBundle(inputDir, outputDir, bundleFiles)
    println("Bundle packaged. Hash: $bundleHash")

    // Step 3: Build unsigned manifest
    // Note: We pass the same inputDir, but the manifest will use our pre-computed bundleHash
    val unsignedManifest = builder.build(
        inputDir = inputDir,
        platform = "macos-arm64",
        buildNumber = System.currentTimeMillis(), // Or from CI build number
        mainClass = "com.myapp.MainKt",
        minShellVersion = 1,
        bundleHash = bundleHash,
        rootAppUpdateUrl = null, // Optional: URL to update the shell app itself
    )

    // Step 4: Sign manifest with Ed25519
    val signedManifest = signer.signManifest(unsignedManifest)

    // Step 5: Write manifest.json to output directory
    val json = Json { prettyPrint = true }
    val manifestJson = json.encodeToString(signedManifest)
    File(outputDir, "manifest.json").writeText(manifestJson)

    println("Bundle created:")
    println("  Build: ${signedManifest.buildNumber}")
    println("  Platform: ${signedManifest.platform}")
    println("  Files: ${signedManifest.files.size}")
    println("  Total size: ${signedManifest.totalSize} bytes")
    println("  Bundle hash: ${signedManifest.bundleHash}")
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
 * ├── manifest.json          # Signed manifest (JSON)
 * ├── bundle.zip             # Full bundle archive
 * └── files/
 *     ├── abc123...          # File contents by SHA-256 hash
 *     ├── def456...
 *     └── ...
 *
 * CLI Usage:
 *   # Generate keys (one-time)
 *   ./gradlew :bundle-creator:run --args="--generate-keys"
 *
 *   # Create bundle
 *   ./gradlew :bundle-creator:run --args="--input build/dist --output build/bundle --platform macos-arm64 --private-key-env BUNDLE_PRIVATE_KEY"
 */
