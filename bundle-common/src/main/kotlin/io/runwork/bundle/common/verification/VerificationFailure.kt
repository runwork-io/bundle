package io.runwork.bundle.common.verification

/**
 * Represents a file that failed verification.
 */
data class VerificationFailure(
    /** Relative path of the file */
    val path: String,
    /** Expected SHA-256 hash */
    val expectedHash: String,
    /** Actual SHA-256 hash (null if file missing) */
    val actualHash: String?,
    /** Reason for failure */
    val reason: String,
)
