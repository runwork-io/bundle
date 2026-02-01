package io.runwork.bundle.bootstrap

import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.verification.VerificationFailure
import java.nio.file.Path

/**
 * Result of validating a bundle.
 */
sealed class BundleValidationResult {
    /**
     * Bundle is valid and ready to launch.
     *
     * @property manifest The validated bundle manifest
     * @property versionPath Path to the version directory containing bundle files
     */
    data class Valid(
        val manifest: BundleManifest,
        val versionPath: Path,
    ) : BundleValidationResult()

    /**
     * No bundle has been downloaded yet.
     *
     * The shell should use BundleUpdater.downloadLatest() to download the initial bundle,
     * then call BundleBootstrap.validate() again.
     */
    data object NoBundleExists : BundleValidationResult()

    /**
     * The bundle requires a newer shell version than what is currently running.
     *
     * @property currentVersion The shell version currently running
     * @property requiredVersion The minimum shell version required by the bundle
     * @property updateUrl Optional URL where the user can download the updated shell
     */
    data class ShellUpdateRequired(
        val currentVersion: Int,
        val requiredVersion: Int,
        val updateUrl: String?,
    ) : BundleValidationResult()

    /**
     * Bundle validation failed.
     *
     * @property reason Human-readable description of the failure
     * @property failures List of specific file verification failures
     */
    data class Failed(
        val reason: String,
        val failures: List<VerificationFailure> = listOf(),
    ) : BundleValidationResult()

    /**
     * A network error occurred during validation.
     *
     * This typically happens when trying to verify the signature but the
     * network is unavailable.
     *
     * @property message Human-readable error message
     */
    data class NetworkError(
        val message: String,
    ) : BundleValidationResult()
}
