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
    private var _versionPath: Path? = null

    /**
     * Initialize the resource resolver from BundleLaunchConfig.
     * Must be called once at app startup before accessing resources.
     *
     * @param config The launch config received in main(args[0])
     * @throws IllegalStateException if already initialized
     */
    fun init(config: BundleLaunchConfig) {
        check(_versionPath == null) { "BundleResources already initialized. Call reset() first if re-initialization is needed." }

        val bundleDir = if (config.bundleSubdirectory.isEmpty()) {
            Path(config.appDataDir)
        } else {
            Path(config.appDataDir).resolve(config.bundleSubdirectory)
        }
        _versionPath = bundleDir.resolve("versions").resolve(config.currentBuildNumber.toString())
    }

    /**
     * Check if the resource resolver has been initialized.
     */
    val isInitialized: Boolean
        get() = _versionPath != null

    /**
     * The version directory path.
     * @throws IllegalStateException if not initialized
     */
    val versionDir: Path
        get() = _versionPath ?: throw IllegalStateException("BundleResources not initialized. Call init() first.")

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
        val (result, _) = findResource(path)
        return result
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
        val (result, searchLocations) = findResource(path)
        return result ?: throw ResourceNotFoundException(path, searchLocations)
    }

    private fun findResource(path: String): Pair<Path?, List<Path>> {
        val resourcesDir = versionDir.resolve("resources")
        val platform = Platform.current
        val searchLocations = listOf(
            resourcesDir.resolve(platform.toString()).resolve(path),
            resourcesDir.resolve(platform.os.id).resolve(path),
            resourcesDir.resolve("common").resolve(path),
        )
        return searchLocations.firstOrNull { it.exists() } to searchLocations
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
        val filename = nativeLibraryFilename(name, Platform.current.os)
        return resolve(filename)
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
        val filename = nativeLibraryFilename(name, Platform.current.os)
        val path = resolveOrThrow(filename)
        System.load(path.toAbsolutePath().toString())
    }

    /**
     * Reset the resolver (for testing only).
     */
    internal fun reset() {
        _versionPath = null
    }

    private fun nativeLibraryFilename(name: String, os: Os): String {
        return when (os) {
            Os.MACOS -> "lib$name.dylib"
            Os.WINDOWS -> "$name.dll"
            Os.LINUX -> "lib$name.so"
        }
    }
}
