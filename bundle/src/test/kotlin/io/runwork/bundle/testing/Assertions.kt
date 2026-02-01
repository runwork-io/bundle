package io.runwork.bundle.testing

import io.runwork.bundle.BundleVerificationResult
import io.runwork.bundle.RepairResult
import io.runwork.bundle.UpdateCheckResult
import io.runwork.bundle.UpdateInfo
import io.runwork.bundle.download.DownloadResult
import io.runwork.bundle.manifest.BundleManifest
import io.runwork.bundle.storage.VerificationFailure
import kotlin.test.assertIs

// ========== UpdateCheckResult Assertions ==========

/**
 * Assert that the update check result indicates the bundle is up to date.
 */
fun UpdateCheckResult.assertUpToDate(block: (Long) -> Unit = {}) {
    assertIs<UpdateCheckResult.UpToDate>(this)
    block(this.buildNumber)
}

/**
 * Assert that an update is available.
 */
fun UpdateCheckResult.assertUpdateAvailable(block: (UpdateInfo) -> Unit = {}) {
    assertIs<UpdateCheckResult.UpdateAvailable>(this)
    block(this.info)
}

// ========== DownloadResult Assertions ==========

/**
 * Assert that the download succeeded.
 */
fun DownloadResult.assertSuccess(block: (Long) -> Unit = {}) {
    assertIs<DownloadResult.Success>(this)
    block(this.buildNumber)
}

/**
 * Assert that the download failed.
 */
fun DownloadResult.assertFailure(block: (String) -> Unit = {}) {
    assertIs<DownloadResult.Failure>(this)
    block(this.error)
}

// ========== BundleVerificationResult Assertions ==========

/**
 * Assert that the bundle is valid.
 */
fun BundleVerificationResult.assertValid(block: (BundleManifest) -> Unit = {}) {
    assertIs<BundleVerificationResult.Valid>(this)
    block(this.manifest)
}

/**
 * Assert that the bundle is corrupted.
 */
fun BundleVerificationResult.assertCorrupted(block: (List<VerificationFailure>) -> Unit = {}) {
    assertIs<BundleVerificationResult.Corrupted>(this)
    block(this.failures)
}

/**
 * Assert that no bundle is installed.
 */
fun BundleVerificationResult.assertNoBundleInstalled() {
    assertIs<BundleVerificationResult.NoBundleInstalled>(this)
}

// ========== RepairResult Assertions ==========

/**
 * Assert that the repair succeeded.
 */
fun RepairResult.assertSuccess() {
    assertIs<RepairResult.Success>(this)
}
