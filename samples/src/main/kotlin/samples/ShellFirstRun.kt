package samples

import io.runwork.bundle.bootstrap.BundleBootstrap
import io.runwork.bundle.bootstrap.BundleBootstrapConfig
import io.runwork.bundle.bootstrap.BundleValidationResult
import io.runwork.bundle.common.Platform
import io.runwork.bundle.updater.BundleUpdater
import io.runwork.bundle.updater.BundleUpdaterConfig
import io.runwork.bundle.updater.BundleUpdateEvent
import kotlinx.coroutines.flow.collect
import java.nio.file.Path

/**
 * Use Case 1: Shell App First-Run (No Bundle)
 *
 * Demonstrates the initial download flow when the shell app starts
 * and no bundle has been downloaded yet.
 */
suspend fun shellFirstRun() {
    val appDataDir = Path.of(System.getProperty("user.home"), ".myapp")

    val bootstrapConfig = BundleBootstrapConfig(
        appDataDir = appDataDir,
        bundleSubdirectory = "bundle", // Bundle files stored in ~/.myapp/bundle/
        baseUrl = "https://updates.myapp.com",
        publicKey = "MCowBQYDK2VwAyEA...", // Ed25519 public key (Base64)
        shellVersion = 1,
        platform = Platform.current, // e.g., "macos-arm64"
        mainClass = "com.myapp.Main", // Entry point for the bundle
    )

    val bootstrap = BundleBootstrap(bootstrapConfig)

    // Step 1: Check if bundle exists and validate
    when (val result = bootstrap.validate()) {
        is BundleValidationResult.NoBundleExists -> {
            println("No bundle found. Downloading...")

            // Step 2: Download initial bundle
            val updaterConfig = BundleUpdaterConfig(
                appDataDir = appDataDir,
                bundleSubdirectory = bootstrapConfig.bundleSubdirectory,
                baseUrl = "https://updates.myapp.com",
                publicKey = bootstrapConfig.publicKey,
                currentBuildNumber = 0, // No bundle exists yet
                platform = bootstrapConfig.platform,
            )
            val updater = BundleUpdater(updaterConfig)
            var downloadedBuildNumber: Long? = null

            // Collect download events from the Flow
            updater.downloadLatest().collect { event ->
                when (event) {
                    BundleUpdateEvent.Checking -> {
                        println("Checking for updates...")
                    }
                    BundleUpdateEvent.UpToDate -> {
                        println("Already up to date")
                    }
                    is BundleUpdateEvent.UpdateAvailable -> {
                        println("Update available: build ${event.info.newBuildNumber}")
                    }
                    is BundleUpdateEvent.Downloading -> {
                        println("Downloading: ${event.progress.bytesDownloaded}/${event.progress.totalBytes} (${event.progress.percentCompleteInt}%)")
                    }
                    is BundleUpdateEvent.BackingOff -> {
                        println("Retry #${event.retryNumber} in ${event.delaySeconds}s: ${event.error.message}")
                    }
                    is BundleUpdateEvent.UpdateReady -> {
                        println("Downloaded build ${event.newBuildNumber}")
                        downloadedBuildNumber = event.newBuildNumber
                    }
                    is BundleUpdateEvent.Error -> {
                        println("Download failed: ${event.error.message}")
                    }
                    is BundleUpdateEvent.CleanupComplete -> {
                        // Cleanup happens after up-to-date check
                    }
                }
            }

            // Step 3: Validate and launch if download succeeded
            if (downloadedBuildNumber != null) {
                when (val validResult = bootstrap.validate()) {
                    is BundleValidationResult.Valid -> {
                        val loadedBundle = bootstrap.launch(validResult)
                        println("Bundle launched: ${loadedBundle.manifest.buildNumber}")
                    }
                    else -> println("Validation failed after download: $validResult")
                }
            }
            updater.close() // Clean up OkHttp client
        }
        is BundleValidationResult.Valid -> {
            // Bundle already exists - launch it
            val loadedBundle = bootstrap.launch(result)
            println("Bundle launched: ${loadedBundle.manifest.buildNumber}")
        }
        is BundleValidationResult.Failed -> {
            println("Bundle validation failed: ${result.reason}")
            result.failures.forEach { failure ->
                println("  - ${failure.path}: ${failure.reason}")
            }
        }
        is BundleValidationResult.ShellUpdateRequired -> {
            println("Shell update required. Current: ${result.currentVersion}, Required: ${result.requiredVersion}")
            result.updateUrl?.let { println("  Download from: $it") }
        }
        is BundleValidationResult.NetworkError -> {
            println("Network error: ${result.message}")
        }
    }
}
