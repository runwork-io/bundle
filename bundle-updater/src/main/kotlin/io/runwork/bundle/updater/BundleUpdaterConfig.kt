package io.runwork.bundle.updater

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.storage.PlatformPaths
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Configuration for the BundleUpdater.
 *
 * Can be constructed from [io.runwork.bundle.common.BundleLaunchConfig] when running
 * inside a bundle for self-updates.
 */
data class BundleUpdaterConfig(
    /** Absolute path to the application data directory containing bundle storage */
    val appDataDir: Path,

    /** Base URL for fetching manifests and bundle files */
    val baseUrl: String,

    /** Base64-encoded Ed25519 public key for manifest signature verification */
    val publicKey: String,

    /** Build number of the currently running bundle (0 if no bundle exists yet) */
    val currentBuildNumber: Long,

    /** Platform (OS and architecture) */
    val platform: Platform = Platform.current(),

    /** Interval between automatic update checks when running as a background service */
    val checkInterval: Duration = 6.hours,
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
     * @param baseUrl Base URL for fetching manifests and bundle files
     * @param publicKey Ed25519 public key for manifest verification (base64 encoded)
     * @param currentBuildNumber Build number of the currently running bundle (0 if no bundle exists yet)
     * @param platform Platform (defaults to current platform)
     * @param checkInterval Interval between automatic update checks
     */
    constructor(
        appId: String,
        baseUrl: String,
        publicKey: String,
        currentBuildNumber: Long,
        platform: Platform = Platform.current(),
        checkInterval: Duration = 6.hours,
    ) : this(
        appDataDir = PlatformPaths.getDefaultAppDataDir(appId),
        baseUrl = baseUrl,
        publicKey = publicKey,
        currentBuildNumber = currentBuildNumber,
        platform = platform,
        checkInterval = checkInterval,
    )
}
