package io.runwork.bundle.common.manifest

import kotlinx.serialization.Serializable

/**
 * Manifest describing a bundle's contents, version, and signature.
 *
 * The manifest is signed with Ed25519 and contains SHA-256 hashes of all files,
 * enabling integrity verification of the entire bundle.
 */
@Serializable
data class BundleManifest(
    /** Schema version for forward compatibility */
    val schemaVersion: Int,

    /** Monotonically increasing build number */
    val buildNumber: Long,

    /** Platform identifier: macos-arm64, macos-x86_64, windows-x86_64, linux-x86_64 */
    val platform: String,

    /** ISO-8601 timestamp when the bundle was created */
    val createdAt: String,

    /** Minimum shell version required to load this bundle */
    val minimumShellVersion: Int,

    /** URL where users can download an updated shell application (optional) */
    val shellUpdateUrl: String? = null,

    /** All files in the bundle with their paths, hashes, and sizes */
    val files: List<BundleFile>,

    /** Fully qualified main class name (e.g., "io.runwork.desktop.MainKt") */
    val mainClass: String,

    /** Total size of all files in bytes */
    val totalSize: Long,

    /** SHA-256 hash of the full bundle.zip, prefixed with "sha256:" */
    val bundleHash: String,

    /** Ed25519 signature of the manifest (excluding this field), prefixed with "ed25519:" */
    val signature: String = "",
)
