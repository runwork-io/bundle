package io.runwork.bundle.common.manifest

import kotlinx.serialization.Serializable

/**
 * A single file within the bundle.
 */
@Serializable
data class BundleFile(
    /** Relative path within the bundle (e.g., "app.jar", "natives/libwhisper.dylib") */
    val path: String,

    /** SHA-256 hash prefixed with "sha256:" */
    val hash: String,

    /** File size in bytes */
    val size: Long,
)
