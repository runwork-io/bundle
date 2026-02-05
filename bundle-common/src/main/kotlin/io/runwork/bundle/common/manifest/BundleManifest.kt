package io.runwork.bundle.common.manifest

import io.runwork.bundle.common.Platform
import kotlinx.serialization.Serializable

/**
 * Manifest describing a bundle's contents, version, and signature.
 *
 * The manifest is signed with Ed25519 and contains SHA-256 hashes of all files,
 * enabling integrity verification of the entire bundle.
 *
 * This is a multi-platform manifest that supports multiple OS/architecture combinations.
 * Each platform has its own bundle zip file, and files can be tagged with platform constraints.
 */
@Serializable
data class BundleManifest(
    /** Schema version for forward compatibility */
    val schemaVersion: Int,

    /** Monotonically increasing build number */
    val buildNumber: Long,

    /** ISO-8601 timestamp when the bundle was created */
    val createdAt: String,

    /** Minimum shell version required to load this bundle */
    val minShellVersion: Int,

    /** URL where users can download an updated shell application (optional) */
    val shellUpdateUrl: String? = null,

    /** All files in the bundle with their paths, hashes, sizes, and optional platform constraints */
    val files: List<BundleFile>,

    /** Fully qualified main class name (e.g., "io.runwork.desktop.MainKt") */
    val mainClass: String,

    /** Map of platform ID (e.g., "macos-arm64") to platform-specific bundle info */
    val zips: Map<String, PlatformBundle>,

    /** Ed25519 signature of the manifest (excluding this field), prefixed with "ed25519:" */
    val signature: String = "",
) {
    /**
     * Get all files that apply to the given platform.
     *
     * This filters files based on their os and arch constraints.
     * Files with no constraints (os=null, arch=null) are included for all platforms.
     */
    fun filesForPlatform(platform: Platform): List<BundleFile> {
        return files.filter { it.appliesTo(platform) }
    }

    /**
     * Check if this manifest supports the given platform.
     *
     * A platform is supported if it has an entry in zips.
     */
    fun supportsPlatform(platform: Platform): Boolean {
        return zips.containsKey(platform.toString())
    }

    /**
     * Get the size of the bundle zip for a specific platform.
     *
     * Returns null if the platform is not supported.
     */
    fun sizeForPlatform(platform: Platform): Long? {
        return zips[platform.toString()]?.size
    }

    /**
     * Get the zip path for a specific platform.
     *
     * Returns null if the platform is not supported.
     */
    fun zipForPlatform(platform: Platform): String? {
        return zips[platform.toString()]?.zip
    }
}
