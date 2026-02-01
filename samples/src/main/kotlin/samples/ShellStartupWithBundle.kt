package samples

import io.runwork.bundle.bootstrap.BundleBootstrap
import io.runwork.bundle.bootstrap.BundleBootstrapConfig
import io.runwork.bundle.bootstrap.BundleBootstrapProgress
import io.runwork.bundle.bootstrap.BundleValidationResult
import io.runwork.bundle.common.Platform
import java.nio.file.Path

/**
 * Use Case 4: Shell App Startup (Bundle Exists)
 *
 * Demonstrates the normal startup flow when the shell app starts
 * and a bundle has already been downloaded.
 */
suspend fun shellStartupWithBundle() {
    val appDataDir = Path.of(System.getProperty("user.home"), ".myapp")

    val config = BundleBootstrapConfig(
        appDataDir = appDataDir,
        bundleSubdirectory = "bundle", // Bundle files stored in ~/.myapp/bundle/
        baseUrl = "https://updates.myapp.com",
        publicKey = "MCowBQYDK2VwAyEA...", // Ed25519 public key (Base64)
        shellVersion = 1,
        platform = Platform.current,
        mainClass = "com.myapp.Main", // Entry point for the bundle
    )

    val bootstrap = BundleBootstrap(config)

    // Validate existing bundle with progress reporting
    when (val result = bootstrap.validate { progress ->
        when (progress) {
            is BundleBootstrapProgress.LoadingManifest -> {
                println("Loading manifest...")
            }
            is BundleBootstrapProgress.VerifyingSignature -> {
                println("Verifying signature...")
            }
            is BundleBootstrapProgress.VerifyingFiles -> {
                val pct = (progress.percentComplete * 100).toInt()
                println("Verifying files: ${progress.filesVerified}/${progress.totalFiles} ($pct%)")
            }
            is BundleBootstrapProgress.Complete -> {
                println("Validation complete")
            }
        }
    }) {
        is BundleValidationResult.Valid -> {
            println("Bundle valid. Build: ${result.manifest.buildNumber}")

            // Launch the bundle in an isolated classloader
            val loadedBundle = bootstrap.launch(result)

            println("Bundle launched successfully")
            println("  Build: ${loadedBundle.manifest.buildNumber}")
            println("  Main class: ${loadedBundle.manifest.mainClass}")
            println("  Version path: ${loadedBundle.versionPath}")

            // Register exit callback (optional)
            loadedBundle.onExit { exception ->
                if (exception != null) {
                    println("Bundle exited with error: ${exception.message}")
                } else {
                    println("Bundle exited normally")
                }
            }

            // The bundle is now running on its own thread (mainThread)
            // Shell can optionally wait for it:
            // loadedBundle.mainThread.join()
        }

        BundleValidationResult.NoBundleExists -> {
            println("No bundle exists - need to download first")
            // Fall back to Use Case 1 (first-run flow)
        }

        is BundleValidationResult.Failed -> {
            println("Bundle validation failed: ${result.reason}")
            result.failures.forEach { failure ->
                println("  - ${failure.path}: ${failure.reason}")
                if (failure.actualHash != null) {
                    println("      Expected: ${failure.expectedHash}")
                    println("      Actual:   ${failure.actualHash}")
                } else {
                    println("      File missing")
                }
            }
            // Options:
            // 1. Delete corrupted bundle and re-download
            // 2. Show error to user
            // 3. Attempt recovery
        }

        is BundleValidationResult.ShellUpdateRequired -> {
            println("Shell update required.")
            println("  Current version: ${result.currentVersion}")
            println("  Required version: ${result.requiredVersion}")
            result.updateUrl?.let { println("  Download from: $it") }
            // Direct user to update the shell app
        }

        is BundleValidationResult.NetworkError -> {
            println("Network error during validation: ${result.message}")
        }
    }
}

/**
 * Storage layout after download:
 *
 * ~/.myapp/
 * └── bundle/                    # Configurable subdirectory (default: "bundle")
 *     ├── manifest.json          # Current manifest
 *     ├── current                # Symlink to active version (Unix)
 *     ├── cas/
 *     │   └── sha256:abc123...   # Content-addressable store
 *     ├── versions/
 *     │   └── 12345/
 *     │       ├── .complete      # Version ready marker
 *     │       └── ...files...    # Hard-linked from CAS
 *     └── temp/                  # Download staging
 *
 * Note: Set bundleSubdirectory = "" to store files directly in ~/.myapp/ (legacy behavior)
 */
