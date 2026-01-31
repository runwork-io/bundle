package io.runwork.bundle.bootstrap

import java.nio.file.Path

/**
 * Configuration for the Bootstrap.
 *
 * Provided by the shell application at startup.
 */
data class BootstrapConfig(
    /** Absolute path to the application data directory containing bundle storage */
    val appDataDir: Path,

    /** Base URL for fetching manifests (used for signature verification context) */
    val baseUrl: String,

    /** Base64-encoded Ed25519 public key for manifest signature verification */
    val publicKey: String,

    /** Platform identifier (e.g., "macos-arm64", "windows-x86_64") */
    val platform: String,

    /** Version of the shell application */
    val shellVersion: Int,

    /** Fully qualified main class name to invoke in the bundle */
    val mainClass: String = "io.runwork.app.Main",
)
