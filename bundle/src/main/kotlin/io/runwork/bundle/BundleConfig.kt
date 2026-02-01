package io.runwork.bundle

import io.runwork.bundle.storage.PlatformPaths
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

    /** Application data directory for bundle storage */
    val appDataDir: Path,

    /** Platform identifier: macos-arm64, macos-x86_64, windows-x86_64, linux-x86_64 */
    val platform: String = PlatformPaths.getPlatform(),
) {
    /**
     * Secondary constructor that uses an application ID to determine the default storage path.
     *
     * The [appId] is used to create the [appDataDir] in platform-specific locations:
     * - macOS: ~/Library/Application Support/{appId}
     * - Windows: %APPDATA%/{appId}
     * - Linux: $XDG_DATA_HOME/{appId} or ~/.local/share/{appId}
     *
     * @param appId Unique application identifier for default storage path resolution
     * @param baseUrl Base URL for manifest and bundle downloads
     * @param publicKey Ed25519 public key for manifest verification (base64 encoded)
     * @param platform Platform identifier (defaults to current platform)
     */
    constructor(
        appId: String,
        baseUrl: String,
        publicKey: String,
        platform: String = PlatformPaths.getPlatform(),
    ) : this(
        baseUrl = baseUrl,
        publicKey = publicKey,
        appDataDir = PlatformPaths.getDefaultAppDataDir(appId),
        platform = platform,
    )
}
