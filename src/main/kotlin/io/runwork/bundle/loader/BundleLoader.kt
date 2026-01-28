package io.runwork.bundle.loader

import io.runwork.bundle.manifest.BundleManifest
import io.runwork.bundle.manifest.FileType
import io.runwork.bundle.storage.StorageManager
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Loads a bundle into an isolated classloader and invokes its main method.
 *
 * The bundle must implement:
 * ```kotlin
 * @JvmStatic
 * fun main(args: Array<String>)
 * ```
 *
 * The bundle may optionally implement a message handler for shell communication:
 * ```kotlin
 * @JvmStatic
 * fun onShellMessage(json: String)
 * ```
 *
 * The first argument to main is the absolute path to the version directory,
 * which the bundle can use to locate its resources and native libraries.
 *
 * The bundle's main method is invoked on a separate thread, so this method
 * returns immediately regardless of whether main() blocks (e.g., for a GUI event loop).
 */
class BundleLoader(
    private val storageManager: StorageManager
) {

    /**
     * Load a bundle and invoke its main method.
     *
     * @param manifest The bundle manifest
     * @return LoadedBundle containing the classloader and version path
     */
    fun load(manifest: BundleManifest): LoadedBundle {
        val versionPath = storageManager.getVersionPath(manifest.buildNumber)

        // Collect all JARs
        val jarUrls = manifest.files
            .filter { it.type == FileType.JAR }
            .map { versionPath.resolve(it.path).toUri().toURL() }
            .toTypedArray()

        // Create isolated classloader
        val bundleClassLoader = BundleClassLoader(jarUrls, ClassLoader.getSystemClassLoader())

        // Load the bundle entry point
        val entryClass = try {
            bundleClassLoader.loadClass(manifest.mainClass)
        } catch (e: ClassNotFoundException) {
            throw BundleLoadException("Main class not found: ${manifest.mainClass}", e)
        }

        // Find the main method
        val mainMethod = try {
            entryClass.getMethod("main", Array<String>::class.java)
        } catch (e: NoSuchMethodException) {
            throw BundleLoadException(
                "Main class ${manifest.mainClass} must have a static main(Array<String>) method",
                e
            )
        }

        // Validate that main is static
        if (!Modifier.isStatic(mainMethod.modifiers)) {
            throw BundleLoadException(
                "Main class ${manifest.mainClass}.main() must be static (use @JvmStatic annotation)"
            )
        }

        // Find optional onShellMessage handler
        val onShellMessageMethod = try {
            val method = entryClass.getMethod("onShellMessage", String::class.java)
            if (!Modifier.isStatic(method.modifiers)) {
                System.err.println("WARNING: ${manifest.mainClass}.onShellMessage() is not static, ignoring")
                null
            } else {
                method
            }
        } catch (e: NoSuchMethodException) {
            null // Optional - bundle doesn't need to handle shell messages
        }

        // Create the message sender function if the method exists
        val sendMessage: ((String) -> Unit)? = onShellMessageMethod?.let { method ->
            { json: String -> method.invoke(null, json) }
        }

        // Exit callback holder - set via onExit after LoadedBundle is created
        val exitCallbackHolder = ExitCallbackHolder()

        // Invoke main on a separate thread so we return immediately.
        // This allows the shell to continue (e.g., start update checker) even if
        // the bundle's main() blocks for a GUI event loop.
        val args = arrayOf(versionPath.toAbsolutePath().toString())
        val mainThread = thread(name = "bundle-main", isDaemon = false) {
            var exception: Throwable? = null
            try {
                mainMethod.invoke(null, args)
            } catch (e: Exception) {
                // InvocationTargetException wraps the real cause
                exception = e.cause ?: e
                System.err.println("Bundle main() threw exception: ${exception.message}")
                exception.printStackTrace()
            } finally {
                exitCallbackHolder.callback?.invoke(exception)
            }
        }

        return LoadedBundle(
            manifest = manifest,
            classLoader = bundleClassLoader,
            versionPath = versionPath,
            sendMessage = sendMessage,
            mainThread = mainThread,
            onExit = { callback -> exitCallbackHolder.callback = callback },
        )
    }
}

/** Thread-safe holder for the exit callback. */
private class ExitCallbackHolder {
    @Volatile
    var callback: ((Throwable?) -> Unit)? = null
}

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

