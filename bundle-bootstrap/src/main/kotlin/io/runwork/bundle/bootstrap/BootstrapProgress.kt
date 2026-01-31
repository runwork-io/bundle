package io.runwork.bundle.bootstrap

/**
 * Progress information during bootstrap validation.
 */
sealed class BootstrapProgress {
    /** Loading and parsing the manifest file */
    data object LoadingManifest : BootstrapProgress()

    /** Verifying the manifest signature */
    data object VerifyingSignature : BootstrapProgress()

    /** Verifying file hashes */
    data class VerifyingFiles(
        val filesVerified: Int,
        val totalFiles: Int,
    ) : BootstrapProgress() {
        val percentComplete: Float
            get() = if (totalFiles > 0) filesVerified.toFloat() / totalFiles else 0f
    }

    /** Validation complete */
    data object Complete : BootstrapProgress()
}
