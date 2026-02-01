package io.runwork.bundle.bootstrap.loader

import io.runwork.bundle.common.manifest.BundleManifest
import java.nio.file.Path

/**
 * A loaded bundle with its classloader.
 */
data class LoadedBundle(
    /** The bundle manifest */
    val manifest: BundleManifest,

    /** The isolated classloader for this bundle */
    val classLoader: BundleClassLoader,

    /** Path to the version directory */
    val versionPath: Path,

    /**
     * Function to send a JSON message to the bundle.
     * Null if the bundle doesn't implement onShellMessage.
     */
    val sendMessage: ((String) -> Unit)?,

    /**
     * The thread running the bundle's main method.
     * Can be used to wait for the bundle to exit or check if it's still running.
     */
    val mainThread: Thread,

    /**
     * Register a callback to be invoked when the bundle's main thread exits.
     *
     * @param callback Function called with the exception if main() threw, or null for clean exit
     */
    val onExit: ((Throwable?) -> Unit) -> Unit,
)

/** Exception thrown when bundle loading fails. */
class BundleLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
