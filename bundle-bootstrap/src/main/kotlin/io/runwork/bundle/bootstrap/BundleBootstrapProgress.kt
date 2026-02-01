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
        val filesVerified: Int,
        val totalFiles: Int,
    ) : BundleBootstrapProgress() {
        val percentComplete: Float
            get() = if (totalFiles > 0) filesVerified.toFloat() / totalFiles else 0f
    }

    /** Validation complete */
    data object Complete : BundleBootstrapProgress()
}
