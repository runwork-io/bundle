package io.runwork.bundle.bootstrap

import io.runwork.bundle.updater.download.DownloadProgress

/**
 * Progress information during [BundleBootstrap.validateAndLaunch].
 */
sealed class BundleStartProgress {
    /** Validating the existing bundle */
    data object Validating : BundleStartProgress()

    /** No valid bundle exists; a download is required */
    data object DownloadRequired : BundleStartProgress()

    /** Downloading the bundle */
    data class Downloading(val progress: DownloadProgress) : BundleStartProgress()

    /** Download completed successfully */
    data object DownloadComplete : BundleStartProgress()

    /** Re-validating the bundle after download */
    data object Revalidating : BundleStartProgress()

    /** Launching the bundle */
    data object Launching : BundleStartProgress()
}
