package io.runwork.bundle.bootstrap

/**
 * Progress information during bootstrap validation.
 */
sealed class BundleBootstrapProgress {
    /** Loading and parsing the manifest file */
    data object LoadingManifest : BundleBootstrapProgress()

    /** Verifying the manifest signature */
    data object VerifyingSignature : BundleBootstrapProgress()

    /** Verifying file hashes */
    data class VerifyingFiles(
        val bytesVerified: Long,
        val totalBytes: Long,
        val filesVerified: Int,
        val totalFiles: Int,
    ) : BundleBootstrapProgress() {
        val percentComplete: Float
            get() = if (totalBytes > 0) bytesVerified.toFloat() / totalBytes else 0f
        val percentCompleteInt: Int
            get() = (percentComplete * 100).toInt()
    }

    /** Validation complete */
    data object Complete : BundleBootstrapProgress()
}
