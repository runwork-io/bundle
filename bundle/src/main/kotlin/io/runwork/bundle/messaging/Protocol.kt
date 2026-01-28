package io.runwork.bundle.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON parser for messages.
 *
 * Uses ignoreUnknownKeys for forward compatibility - new message fields
 * can be added without breaking older shells/bundles.
 */
val messageJson = Json {
    ignoreUnknownKeys = true
}

// ============================================================================
// Shell -> Bundle Messages
// ============================================================================

/**
 * Base class for messages from shell to bundle.
 *
 * The type discriminator is handled by kotlinx.serialization using @SerialName
 * on each subclass. No need for an explicit type property.
 */
@Serializable
sealed interface ShellMessage

/**
 * Initialize the bundle with shell information.
 */
@Serializable
@SerialName("initialize")
data class InitializeMessage(
    val shellVersion: Int,
    val platform: String,
    val appDataDir: String,
    val bundleVersion: Long,
) : ShellMessage

/**
 * Request the bundle to shut down.
 */
@Serializable
@SerialName("shutdown")
data class ShutdownMessage(
    val reason: String? = null,
) : ShellMessage

/**
 * Notify bundle of window focus change.
 */
@Serializable
@SerialName("focus")
data class FocusMessage(
    val focused: Boolean,
) : ShellMessage

/**
 * Notify bundle that an update is available.
 */
@Serializable
@SerialName("update_available")
data class UpdateAvailableMessage(
    val buildNumber: Long,
    val currentBuildNumber: Long,
) : ShellMessage

/**
 * Notify bundle of download progress.
 */
@Serializable
@SerialName("download_progress")
data class DownloadProgressMessage(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentComplete: Float,
) : ShellMessage

/**
 * Notify bundle that update is ready to apply.
 */
@Serializable
@SerialName("update_ready")
data class UpdateReadyMessage(
    val buildNumber: Long,
) : ShellMessage

// ============================================================================
// Bundle -> Shell Messages
// ============================================================================

/**
 * Base class for messages from bundle to shell.
 *
 * The type discriminator is handled by kotlinx.serialization using @SerialName
 * on each subclass. No need for an explicit type property.
 */
@Serializable
sealed interface BundleMessage

/**
 * Bundle is ready and running.
 */
@Serializable
@SerialName("ready")
data class ReadyMessage(
    val bundleVersion: Long,
) : BundleMessage

/**
 * Request the shell to check for updates.
 */
@Serializable
@SerialName("check_updates")
data object CheckUpdatesMessage : BundleMessage

/**
 * Request the shell to download an available update.
 */
@Serializable
@SerialName("download_update")
data class DownloadUpdateMessage(
    val buildNumber: Long,
) : BundleMessage

/**
 * Request the shell to apply the downloaded update (restart).
 */
@Serializable
@SerialName("apply_update")
data class ApplyUpdateMessage(
    val buildNumber: Long,
) : BundleMessage

/**
 * Log message from bundle to shell.
 */
@Serializable
@SerialName("log")
data class LogMessage(
    val level: LogLevel,
    val message: String,
    val timestamp: String? = null,
) : BundleMessage

/**
 * Log levels for bundle logging.
 */
@Serializable
enum class LogLevel {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

/**
 * Error message from bundle.
 */
@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
) : BundleMessage
