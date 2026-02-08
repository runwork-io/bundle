package io.runwork.bundle.resources

import io.runwork.bundle.common.BundleLaunchConfig
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Resource resolver for bundle applications.
 *
 * Initialize once at app startup, then access resources statically from anywhere.
 *
 * Resolves resource paths using platform-specific priority:
 * 1. resources/{os}-{arch}/{path} (e.g., resources/macos-arm64/config.json)
 * 2. resources/{os}/{path} (e.g., resources/macos/config.json)
 * 3. resources/common/{path} (e.g., resources/common/config.json)
 *
 * Resources must be in the resources/ folder.
 *
 * ## Usage Example
 *
 * ```kotlin
 * fun main(args: Array<String>) {
 *     val config = Json.decodeFromString<BundleLaunchConfig>(args[0])
 *     BundleResources.init(config)
 *
 *     // Now access from anywhere
 *     val settingsPath = BundleResources.resolveOrThrow("config/settings.json")
 *     BundleResources.loadNativeLibrary("whisper")
 * }
 * ```
 */
object BundleResources {
    @Volatile
    private var _state: State? = null

    private val state: State
        get() = _state ?: error("BundleResources not initialized. Call init() first.")

    val isInitialized: Boolean
        get() = _state != null

    internal val versionDir: Path
        get() = state.versionDir

    /**
     * Initialize the resource resolver from BundleLaunchConfig.
     * Must be called once at app startup before accessing resources.
     *
     * @param config The launch config received in main(args[0])
     */
    fun init(config: BundleLaunchConfig) {
        val bundleDir = if (config.bundleSubdirectory.isEmpty()) {
            Path(config.appDataDir)
        } else {
            Path(config.appDataDir).resolve(config.bundleSubdirectory)
        }
        val versionDir = bundleDir.resolve("versions").resolve(config.currentBuildNumber.toString())
        val resourcesDir = versionDir.resolve("resources")
        val platform = Platform.current

        _state = State(
            versionDir = versionDir,
            platformDir = resourcesDir.resolve(platform.toString()),
            osDir = resourcesDir.resolve(platform.os.id),
            commonDir = resourcesDir.resolve("common"),
        )
    }

    /**
     * Resolve a resource path with platform priority.
     *
     * Searches in order:
     * 1. resources/{os}-{arch}/{path} (exact platform match)
     * 2. resources/{os}/{path} (OS-only match)
     * 3. resources/common/{path} (universal)
     *
     * @param path Relative path within resources folder (e.g., "config/app.json")
     * @return Absolute path to the resource, or null if not found in any location
     * @throws IllegalStateException if not initialized
     */
    fun resolve(path: String): Path? {
        val s = state
        s.platformDir.resolve(path).let { if (it.exists()) return it }
        s.osDir.resolve(path).let { if (it.exists()) return it }
        s.commonDir.resolve(path).let { if (it.exists()) return it }
        return null
    }

    /**
     * Resolve a resource path, throwing if not found.
     *
     * @param path Relative path within resources folder
     * @return Absolute path to the resource
     * @throws ResourceNotFoundException if resource doesn't exist in any location
     * @throws IllegalStateException if not initialized
     */
    fun resolveOrThrow(path: String): Path {
        val s = state
        s.platformDir.resolve(path).let { if (it.exists()) return it }
        s.osDir.resolve(path).let { if (it.exists()) return it }
        s.commonDir.resolve(path).let { if (it.exists()) return it }

        throw ResourceNotFoundException(
            path,
            listOf(
                s.platformDir.resolve(path),
                s.osDir.resolve(path),
                s.commonDir.resolve(path),
            ),
        )
    }

    /**
     * Resolve a native library by base name.
     *
     * Automatically applies platform-specific naming:
     * - macOS: lib{name}.dylib
     * - Windows: {name}.dll
     * - Linux: lib{name}.so
     *
     * @param name Base name without prefix/extension (e.g., "foo" not "libfoo.dylib")
     * @return Absolute path to the native library, or null if not found
     * @throws IllegalStateException if not initialized
     */
    fun resolveNativeLibrary(name: String): Path? {
        return resolve(nativeLibraryFilename(name))
    }

    /**
     * Load a native library by base name.
     *
     * Resolves the library path and calls System.load().
     *
     * @param name Base name without prefix/extension
     * @throws ResourceNotFoundException if library doesn't exist
     * @throws UnsatisfiedLinkError if library fails to load
     * @throws IllegalStateException if not initialized
     */
    fun loadNativeLibrary(name: String) {
        val path = resolveOrThrow(nativeLibraryFilename(name))
        System.load(path.toAbsolutePath().toString())
    }

    private fun nativeLibraryFilename(name: String): String {
        return when (Os.current) {
            Os.MACOS -> "lib$name.dylib"
            Os.WINDOWS -> "$name.dll"
            Os.LINUX -> "lib$name.so"
        }
    }

    private class State(
        val versionDir: Path,
        val platformDir: Path,
        val osDir: Path,
        val commonDir: Path,
    )
}
