package io.runwork.bundle.bootstrap

import io.runwork.bundle.updater.download.DownloadProgress

/**
 * Events emitted during [BundleBootstrap.validateAndLaunch].
 *
 * Progress events report forward motion; terminal events ([Failed], [ShellUpdateRequired])
 * indicate that the flow will complete normally without launching.
 * On successful launch + bundle exit, the process exits via `exitProcess(0)`.
 */
sealed class BundleStartEvent {
    sealed class Progress : BundleStartEvent() {
        /** Validating the existing bundle */
        data object Validating : Progress()

        /** Downloading the bundle */
        data class Downloading(val progress: DownloadProgress) : Progress()

        /** Launching the bundle */
        data object Launching : Progress()
    }

    /** Starting the bundle failed. */
    data class Failed(
        val reason: String,
        val cause: Throwable? = null,
        val isRetryable: Boolean = false,
    ) : BundleStartEvent()

    /** The bundle requires a newer shell version than what is currently running. */
    data class ShellUpdateRequired(
        val currentVersion: Int,
        val requiredVersion: Int,
        val updateUrl: String?,
    ) : BundleStartEvent()
}
