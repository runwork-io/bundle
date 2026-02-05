package io.runwork.bundle.common.manifest

import io.runwork.bundle.common.Arch
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import kotlinx.serialization.Serializable

/**
 * A single file within the bundle.
 */
@Serializable
data class BundleFile(
    /** Relative path within the bundle (e.g., "app.jar", "natives/libwhisper.dylib") */
    val path: String,

    /** SHA-256 hash of the file */
    val hash: BundleFileHash,

    /** File size in bytes */
    val size: Long,

    /** OS constraint for this file (null = all operating systems) */
    val os: Os? = null,

    /** Architecture constraint for this file (null = all architectures) */
    val arch: Arch? = null,
) {
    /**
     * Check if this file applies to the given platform.
     *
     * A file applies if:
     * - Both os and arch are null (universal file), OR
     * - os matches (or is null) AND arch matches (or is null)
     */
    fun appliesTo(platform: Platform): Boolean {
        val osMatches = os == null || os == platform.os
        val archMatches = arch == null || arch == platform.arch
        return osMatches && archMatches
    }
}
