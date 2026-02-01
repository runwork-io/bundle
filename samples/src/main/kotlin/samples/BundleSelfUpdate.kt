package samples

import io.runwork.bundle.common.BundleLaunchConfig
import io.runwork.bundle.updater.BundleUpdater
import io.runwork.bundle.updater.BundleUpdateEvent
import io.runwork.bundle.updater.BundleUpdaterConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

/**
 * Use Case 2: Bundle Self-Update (Running Bundle)
 *
 * This code runs INSIDE the bundle (not in the shell).
 * The bundle manages its own updates using background checking.
 */
fun main(args: Array<String>) {
    // Parse config passed from shell via args[0]
    val json = Json { ignoreUnknownKeys = true }
    val launchConfig = json.decodeFromString<BundleLaunchConfig>(args[0])

    println("Bundle started. Build: ${launchConfig.currentBuildNumber}")

    // Start background update checker
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    scope.launch {
        startUpdateChecker(launchConfig)
    }

    // Run your app...
    runApplication()
}

suspend fun startUpdateChecker(launchConfig: BundleLaunchConfig) {
    val config = BundleUpdaterConfig(
        appDataDir = Path.of(launchConfig.appDataDir),
        baseUrl = launchConfig.baseUrl,
        publicKey = launchConfig.publicKey,
        platform = launchConfig.platform,
        currentBuildNumber = launchConfig.currentBuildNumber,
        checkInterval = 6.hours, // Default: check every 6 hours
    )

    val updater = BundleUpdater(config)

    // Collect update events from the Flow
    updater.start().collect { event ->
        when (event) {
            BundleUpdateEvent.Checking -> {
                println("Checking for updates...")
            }
            BundleUpdateEvent.UpToDate -> {
                println("Already running latest version")
            }
            is BundleUpdateEvent.UpdateAvailable -> {
                println("Update available: ${event.info.currentBuildNumber} -> ${event.info.newBuildNumber}")
                println("Download size: ${event.info.downloadSize} bytes")
                println("Incremental: ${event.info.isIncremental}")
            }
            is BundleUpdateEvent.Downloading -> {
                println("Downloading: ${event.progress.percentCompleteInt}%")
            }
            is BundleUpdateEvent.UpdateReady -> {
                println("Update ready! Build ${event.newBuildNumber}")
                // Prompt user to restart, or auto-restart
                promptUserToRestart()
            }
            is BundleUpdateEvent.Error -> {
                println("Update error: ${event.error.message}")
                if (!event.error.isRecoverable) {
                    println("  This error is not recoverable")
                }
            }
            is BundleUpdateEvent.CleanupComplete -> {
                println("Cleaned up ${event.result.versionsRemoved.size} old versions")
                println("Freed ${event.result.bytesFreed} bytes")
            }
        }
    }
}

fun promptUserToRestart() {
    // Show UI dialog, or auto-restart
    // On restart, the shell will launch the new version
}

fun runApplication() {
    // Your app logic here
}
