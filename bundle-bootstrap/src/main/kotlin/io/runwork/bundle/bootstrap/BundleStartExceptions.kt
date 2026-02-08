package io.runwork.bundle.bootstrap

/**
 * The bundle requires a newer shell version than what is currently running.
 *
 * Thrown by [BundleBootstrap.validateAndLaunch] when the manifest's `minShellVersion`
 * exceeds the shell's current version.
 *
 * @property currentVersion The shell version currently running
 * @property requiredVersion The minimum shell version required by the bundle
 * @property updateUrl Optional URL where the user can download the updated shell
 */
class ShellUpdateRequiredException(
    val currentVersion: Int,
    val requiredVersion: Int,
    val updateUrl: String?,
) : Exception("Shell update required: current=$currentVersion, required=$requiredVersion")

/**
 * Starting the bundle failed.
 *
 * Thrown by [BundleBootstrap.validateAndLaunch] when the bundle cannot be started.
 *
 * @property reason Human-readable description of the failure
 * @property isRetryable Whether the caller should retry (e.g., network error vs signature failure)
 */
class BundleStartException(
    val reason: String,
    cause: Throwable? = null,
    val isRetryable: Boolean = false,
) : Exception(reason, cause)
