package io.runwork.bundle.updater

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Configuration for the BundleUpdater.
 *
 * Can be constructed from [io.runwork.bundle.common.BundleLaunchConfig] when running
 * inside a bundle for self-updates.
 */
data class UpdaterConfig(
    /** Absolute path to the application data directory containing bundle storage */
    val appDataDir: Path,

    /** Base URL for fetching manifests and bundle files */
    val baseUrl: String,

    /** Base64-encoded Ed25519 public key for manifest signature verification */
    val publicKey: String,

    /** Platform identifier (e.g., "macos-arm64", "windows-x86_64") */
    val platform: String,

    /** Build number of the currently running bundle (0 if no bundle exists yet) */
    val currentBuildNumber: Long,

    /** Interval between automatic update checks when running as a background service */
    val checkInterval: Duration = 6.hours,
)
