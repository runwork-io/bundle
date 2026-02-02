package io.runwork.bundle.common.manifest

import kotlinx.serialization.Serializable

/**
 * Platform-specific bundle information within a multi-platform manifest.
 */
@Serializable
data class PlatformBundle(
    /** Relative path to the bundle zip (e.g., "bundle-macos-arm64.zip") */
    val bundleZip: String,

    /** Total size of files for this platform in bytes (used for download strategy decisions) */
    val totalSize: Long,
)
