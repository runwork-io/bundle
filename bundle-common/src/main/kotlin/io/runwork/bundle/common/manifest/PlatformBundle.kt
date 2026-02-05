package io.runwork.bundle.common.manifest

import kotlinx.serialization.Serializable

/**
 * Platform-specific bundle information within a multi-platform manifest.
 */
@Serializable
data class PlatformBundle(
    /** Relative path to the bundle zip (e.g., "bundle-macos-arm64.zip") */
    val bundleZip: String,

    /** Size of the platform bundle zip in bytes (used for download strategy decisions and progress reporting) */
    val size: Long,
)
