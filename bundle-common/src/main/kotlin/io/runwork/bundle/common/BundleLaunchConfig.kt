package io.runwork.bundle.common

import kotlinx.serialization.Serializable

/**
 * Configuration passed from the shell application (bootstrap) to the bundle's main() method.
 *
 * This is serialized to JSON and passed as args[0] when launching a bundle.
 * The bundle can use this information to initialize the BundleUpdater for self-updates.
 */
@Serializable
data class BundleLaunchConfig(
    /** Absolute path to the application data directory containing bundle storage */
    val appDataDir: String,

    /** Base URL for fetching manifests and bundle files */
    val baseUrl: String,

    /** Base64-encoded Ed25519 public key for manifest signature verification */
    val publicKey: String,

    /** Platform identifier (e.g., "macos-arm64", "windows-x86_64") */
    val platform: String,

    /** Version of the shell application that launched this bundle */
    val shellVersion: Int,

    /** Build number of the currently running bundle */
    val currentBuildNumber: Long,
)
