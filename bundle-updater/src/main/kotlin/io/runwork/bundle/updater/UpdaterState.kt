package io.runwork.bundle.updater

import io.runwork.bundle.updater.download.DownloadProgress

/**
 * Current state of the BundleUpdater when running as a background service.
 */
sealed class UpdaterState {
    /** BundleUpdater is idle, waiting for the next scheduled check */
    data object Idle : UpdaterState()

    /** BundleUpdater is checking for updates */
    data object Checking : UpdaterState()

    /** BundleUpdater is downloading an update */
    data class Downloading(val progress: DownloadProgress) : UpdaterState()

    /** An update has been downloaded and is ready to apply */
    data class Ready(val newBuildNumber: Long) : UpdaterState()

    /** An error occurred during the last operation */
    data class Error(val error: UpdateError) : UpdaterState()
}
