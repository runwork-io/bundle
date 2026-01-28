package io.runwork.bundle

import java.nio.file.Path

/**
 * Configuration for the bundle system.
 */
data class BundleConfig(
    /**
     * Base URL for manifest and bundle downloads.
     *
     * Supports both HTTP/HTTPS URLs (e.g., "https://bundles.runwork.io") and
     * file:// URLs for local testing (e.g., "file:///path/to/bundle-server").
     *
     * For file:// URLs, the expected directory structure is:
     * ```
     * /path/to/bundle-server/
     *   manifest.json           # Bundle manifest JSON
     *   bundle.zip              # Full bundle ZIP (for full downloads)
     *   files/
     *     <hash1>               # Individual files by hash (no sha256: prefix)
     *     <hash2>
     * ```
     */
    val baseUrl: String,

    /** Ed25519 public key for manifest verification (base64 encoded) */
    val publicKey: String,

    /** Platform identifier: macos-arm64, macos-x86_64, windows-x86_64, linux-x86_64 */
    val platform: String,

    /** Application data directory for bundle storage */
    val appDataDir: Path,
)
