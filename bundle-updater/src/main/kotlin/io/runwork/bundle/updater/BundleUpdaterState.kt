package io.runwork.bundle.updater

import io.runwork.bundle.updater.download.DownloadProgress

/**
 * Current state of the BundleUpdater when running as a background service.
 */
sealed class BundleUpdaterState {
    /** BundleUpdater is idle, waiting for the next scheduled check */
    data object Idle : BundleUpdaterState()

    /** BundleUpdater is checking for updates */
    data object Checking : BundleUpdaterState()

    /** BundleUpdater is downloading an update */
    data class Downloading(val progress: DownloadProgress) : BundleUpdaterState()

    /** An update has been downloaded and is ready to apply */
    data class Ready(val newBuildNumber: Long) : BundleUpdaterState()

    /** An error occurred during the last operation */
    data class Error(val error: UpdateError) : BundleUpdaterState()
}
