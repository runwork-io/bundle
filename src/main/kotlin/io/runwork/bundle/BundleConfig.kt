package io.runwork.bundle

import java.nio.file.Path

/**
 * Configuration for the bundle system.
 */
data class BundleConfig(
    /** Base URL for manifest and bundle downloads (e.g., "https://bundles.runwork.io") */
    val baseUrl: String,

    /** Ed25519 public key for manifest verification (base64 encoded) */
    val publicKey: String,

    /** Platform identifier: macos-arm64, macos-x86_64, windows-x86_64, linux-x86_64 */
    val platform: String,

    /** Application data directory for bundle storage */
    val appDataDir: Path,
)
