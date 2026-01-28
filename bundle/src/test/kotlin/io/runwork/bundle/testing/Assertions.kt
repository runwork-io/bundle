package io.runwork.bundle.testing

import io.runwork.bundle.BundleVerificationResult
import io.runwork.bundle.RepairResult
import io.runwork.bundle.UpdateCheckResult
import io.runwork.bundle.UpdateInfo
import io.runwork.bundle.download.DownloadResult
import io.runwork.bundle.manifest.BundleManifest
import io.runwork.bundle.storage.VerificationFailure
import kotlin.test.assertIs
import kotlin.test.assertEquals

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

/**
 * Assert that a network error occurred.
 */
fun UpdateCheckResult.assertNetworkError(block: (String) -> Unit = {}) {
    assertIs<UpdateCheckResult.NetworkError>(this)
    block(this.message)
}

/**
 * Assert that the manifest signature is invalid.
 */
fun UpdateCheckResult.assertSignatureInvalid(block: (String) -> Unit = {}) {
    assertIs<UpdateCheckResult.SignatureInvalid>(this)
    block(this.message)
}

/**
 * Assert that there is a platform mismatch.
 */
fun UpdateCheckResult.assertPlatformMismatch(
    expected: String? = null,
    actual: String? = null,
    block: (UpdateCheckResult.PlatformMismatch) -> Unit = {}
) {
    assertIs<UpdateCheckResult.PlatformMismatch>(this)
    if (expected != null) {
        assertEquals(expected, this.expected, "Expected platform mismatch")
    }
    if (actual != null) {
        assertEquals(actual, this.actual, "Actual platform mismatch")
    }
    block(this)
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

/**
 * Assert that the download was cancelled.
 */
fun DownloadResult.assertCancelled() {
    assertIs<DownloadResult.Cancelled>(this)
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

/**
 * Assert that the repair failed.
 */
fun RepairResult.assertFailure(block: (String) -> Unit = {}) {
    assertIs<RepairResult.Failure>(this)
    block(this.error)
}
