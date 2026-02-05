package io.runwork.bundle.common.verification

import io.runwork.bundle.common.manifest.BundleFileHash

/**
 * Represents a file that failed verification.
 */
data class VerificationFailure(
    /** Relative path of the file */
    val path: String,
    /** Expected hash */
    val expectedHash: BundleFileHash,
    /** Actual hash (null if file missing) */
    val actualHash: BundleFileHash?,
    /** Reason for failure */
    val reason: String,
)
