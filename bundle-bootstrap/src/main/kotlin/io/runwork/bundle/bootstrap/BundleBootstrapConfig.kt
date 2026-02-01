package io.runwork.bundle.bootstrap

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.storage.PlatformPaths
import java.nio.file.Path

/**
 * Configuration for [BundleBootstrap].
 *
 * Provided by the shell application at startup.
 */
data class BundleBootstrapConfig(
    /** Absolute path to the application data directory containing bundle storage */
    val appDataDir: Path,

    /** Base URL for fetching manifests (used for signature verification context) */
    val baseUrl: String,

    /** Base64-encoded Ed25519 public key for manifest signature verification */
    val publicKey: String,

    /** Version of the shell application */
    val shellVersion: Int,

    /** Platform (OS and architecture) */
    val platform: Platform = Platform.current,

    /** Fully qualified main class name to invoke in the bundle */
    val mainClass: String = "io.runwork.app.Main",
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
     * @param baseUrl Base URL for fetching manifests
     * @param publicKey Ed25519 public key for manifest verification (base64 encoded)
     * @param shellVersion Version of the shell application
     * @param platform Platform (defaults to current platform)
     * @param mainClass Fully qualified main class name to invoke in the bundle
     */
    constructor(
        appId: String,
        baseUrl: String,
        publicKey: String,
        shellVersion: Int,
        platform: Platform = Platform.current,
        mainClass: String = "io.runwork.app.Main",
    ) : this(
        appDataDir = PlatformPaths.getDefaultAppDataDir(appId),
        baseUrl = baseUrl,
        publicKey = publicKey,
        shellVersion = shellVersion,
        platform = platform,
        mainClass = mainClass,
    )
}
