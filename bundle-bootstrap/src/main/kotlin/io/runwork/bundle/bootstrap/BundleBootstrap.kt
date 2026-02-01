package io.runwork.bundle.bootstrap

import io.runwork.bundle.bootstrap.loader.BundleClassLoader
import io.runwork.bundle.bootstrap.loader.BundleLoadException
import io.runwork.bundle.bootstrap.loader.LoadedBundle
import io.runwork.bundle.common.BundleLaunchConfig
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.storage.ContentAddressableStore
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.common.verification.SignatureVerifier
import io.runwork.bundle.common.verification.VerificationFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Validates and launches bundles.
 *
 * Provides a two-phase API:
 * 1. [validate] - Validates the bundle without launching (returns [BundleValidationResult])
 * 2. [launch] - Launches a validated bundle (only call with [BundleValidationResult.Valid])
 *
 * This separation allows the shell to:
 * - Show appropriate UI based on validation result
 * - Handle missing bundles by calling BundleUpdater.downloadLatest()
 * - Handle shell updates before attempting to launch
 */
class BundleBootstrap(
    private val config: BundleBootstrapConfig
) {
    private val versionsDir = config.bundleDir.resolve("versions")
    private val manifestPath = config.bundleDir.resolve("manifest.json")
    private val casDir = config.bundleDir.resolve("cas")
    private val contentStore = ContentAddressableStore(casDir)
    private val signatureVerifier = SignatureVerifier(config.publicKey)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Phase 1: Validate the bundle.
     *
     * Checks:
     * 1. Manifest exists and is valid JSON
     * 2. Manifest signature is valid
     * 3. Platform matches
     * 4. Shell version meets minimum requirement
     * 5. All files exist and have correct hashes
     *
     * @param onProgress Called with progress updates during validation
     * @return Validation result indicating success or specific failure mode
     */
    suspend fun validate(
        onProgress: (BundleBootstrapProgress) -> Unit = {}
    ): BundleValidationResult {
        onProgress(BundleBootstrapProgress.LoadingManifest)

        // Load manifest
        val manifestJson = withContext(Dispatchers.IO) {
            if (!Files.exists(manifestPath)) return@withContext null
            try {
                Files.readString(manifestPath)
            } catch (e: Exception) {
                null
            }
        }

        if (manifestJson == null) {
            return BundleValidationResult.NoBundleExists
        }

        val manifest = try {
            json.decodeFromString<BundleManifest>(manifestJson)
        } catch (e: Exception) {
            return BundleValidationResult.Failed("Failed to parse manifest: ${e.message}")
        }

        onProgress(BundleBootstrapProgress.VerifyingSignature)

        // Verify signature
        if (!signatureVerifier.verifyManifest(manifest)) {
            return BundleValidationResult.Failed("Manifest signature verification failed")
        }

        // Check platform
        if (manifest.platform != config.platform.toString()) {
            return BundleValidationResult.Failed(
                "Platform mismatch: manifest is for ${manifest.platform}, but running on ${config.platform}"
            )
        }

        // Check shell version
        if (config.shellVersion < manifest.minimumShellVersion) {
            return BundleValidationResult.ShellUpdateRequired(
                currentVersion = config.shellVersion,
                requiredVersion = manifest.minimumShellVersion,
                updateUrl = manifest.rootAppUpdateUrl,
            )
        }

        // Check version directory exists
        // Note: If manifest.json exists and points to this version, the version is guaranteed
        // to be complete (manifest.json is only saved after prepareVersion() succeeds)
        val versionDir = versionsDir.resolve(manifest.buildNumber.toString())

        if (!withContext(Dispatchers.IO) { Files.exists(versionDir) }) {
            // Version directory missing - this could happen if:
            // - Download was interrupted before manifest.json was updated (shouldn't happen)
            // - Files were manually deleted
            return BundleValidationResult.NoBundleExists
        }

        // Verify CAS files and repair version directory links (parallel with limit of 5)
        val totalFiles = manifest.files.size
        onProgress(BundleBootstrapProgress.VerifyingFiles(0, totalFiles))

        val semaphore = Semaphore(5)
        val failures = coroutineScope {
            manifest.files.map { file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        verifyFileAndLink(file, versionDir)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        onProgress(BundleBootstrapProgress.VerifyingFiles(totalFiles, totalFiles))

        if (failures.isNotEmpty()) {
            return BundleValidationResult.Failed(
                reason = "File verification failed for ${failures.size} file(s)",
                failures = failures,
            )
        }

        onProgress(BundleBootstrapProgress.Complete)

        return BundleValidationResult.Valid(
            manifest = manifest,
            versionPath = versionDir,
        )
    }

    /**
     * Phase 2: Launch a validated bundle.
     *
     * Loads the bundle into an isolated classloader and invokes its main method.
     * The main method runs on a separate thread, so this method returns immediately.
     *
     * @param validation A [BundleValidationResult.Valid] from a successful [validate] call
     * @return LoadedBundle containing the classloader and control methods
     * @throws BundleLoadException if the bundle fails to load
     */
    fun launch(validation: BundleValidationResult.Valid): LoadedBundle {
        val manifest = validation.manifest
        val versionPath = validation.versionPath

        // Collect all JARs
        val jarUrls = manifest.files
            .filter { it.path.endsWith(".jar") }
            .map { versionPath.resolve(it.path).toUri().toURL() }
            .toTypedArray()

        // Create isolated classloader
        val bundleClassLoader = BundleClassLoader(jarUrls, ClassLoader.getSystemClassLoader())

        // Use mainClass from manifest, falling back to config if needed
        val mainClassName = manifest.mainClass.ifEmpty { config.mainClass }

        // Load the bundle entry point
        val entryClass = try {
            bundleClassLoader.loadClass(mainClassName)
        } catch (e: ClassNotFoundException) {
            bundleClassLoader.close()
            throw BundleLoadException("Main class not found: $mainClassName", e)
        }

        // Find the main method
        val mainMethod = try {
            entryClass.getMethod("main", Array<String>::class.java)
        } catch (e: NoSuchMethodException) {
            bundleClassLoader.close()
            throw BundleLoadException(
                "Main class $mainClassName must have a static main(Array<String>) method",
                e
            )
        }

        // Validate that main is static
        if (!Modifier.isStatic(mainMethod.modifiers)) {
            bundleClassLoader.close()
            throw BundleLoadException(
                "Main class $mainClassName.main() must be static (use @JvmStatic annotation)"
            )
        }

        // Find optional onShellMessage handler
        val onShellMessageMethod = try {
            val method = entryClass.getMethod("onShellMessage", String::class.java)
            if (!Modifier.isStatic(method.modifiers)) {
                System.err.println("WARNING: $mainClassName.onShellMessage() is not static, ignoring")
                null
            } else {
                method
            }
        } catch (e: NoSuchMethodException) {
            null // Optional - bundle doesn't need to handle shell messages
        }

        // Create the message sender function if the method exists
        val sendMessage: ((String) -> Unit)? = onShellMessageMethod?.let { method ->
            { jsonMessage: String -> method.invoke(null, jsonMessage) }
        }

        // Build launch config to pass to bundle
        val launchConfig = BundleLaunchConfig(
            appDataDir = config.appDataDir.toAbsolutePath().toString(),
            bundleSubdirectory = config.bundleSubdirectory,
            baseUrl = config.baseUrl,
            publicKey = config.publicKey,
            platform = config.platform.toString(),
            shellVersion = config.shellVersion,
            currentBuildNumber = manifest.buildNumber,
        )
        val launchConfigJson = json.encodeToString(launchConfig)

        // Exit callback holder - set via onExit after LoadedBundle is created
        val exitCallbackHolder = ExitCallbackHolder()

        // Invoke main on a separate thread so we return immediately.
        // This allows the shell to continue (e.g., handle other tasks) even if
        // the bundle's main() blocks for a GUI event loop.
        val args = arrayOf(launchConfigJson)
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

    /**
     * Verify a single file's CAS entry and repair the version directory link if needed.
     *
     * @return VerificationFailure if verification failed, null if successful (including after repair)
     */
    private suspend fun verifyFileAndLink(
        file: io.runwork.bundle.common.manifest.BundleFile,
        versionDir: Path
    ): VerificationFailure? {
        val casFile = contentStore.getPath(file.hash)

        // 1. Verify CAS file exists
        if (casFile == null || !Files.exists(casFile)) {
            return VerificationFailure(
                path = file.path,
                expectedHash = file.hash,
                actualHash = null,
                reason = "CAS file missing"
            )
        }

        // 2. Verify CAS file hash
        val actualHash = HashVerifier.computeHash(casFile)
        if (actualHash != file.hash) {
            return VerificationFailure(
                path = file.path,
                expectedHash = file.hash,
                actualHash = actualHash,
                reason = "CAS file corrupted"
            )
        }

        // 3. Check/repair version directory link
        val versionFile = versionDir.resolve(file.path)
        val needsRepair = !Files.exists(versionFile) || !isSameFile(versionFile, casFile)

        if (needsRepair) {
            // Repair: create link using platform-appropriate method
            try {
                Files.createDirectories(versionFile.parent)
                Files.deleteIfExists(versionFile)
                createLink(versionFile, casFile)
            } catch (e: Exception) {
                return VerificationFailure(
                    path = file.path,
                    expectedHash = file.hash,
                    actualHash = actualHash,
                    reason = "Failed to create link: ${e.message}"
                )
            }
        }

        return null // Success
    }

    /**
     * Create a link from dest to source using the appropriate method for the platform.
     * - macOS/Linux: relative symlink (survives directory moves)
     * - Windows: hard link (symlinks require elevated permissions)
     */
    private fun createLink(dest: Path, source: Path) {
        when (Os.current) {
            Os.WINDOWS -> Files.createLink(dest, source)
            Os.MACOS, Os.LINUX -> {
                val relativeSource = dest.parent.relativize(source)
                Files.createSymbolicLink(dest, relativeSource)
            }
        }
    }

    /**
     * Check if two paths refer to the same file.
     * Uses Files.isSameFile which works correctly for both hard links and symlinks.
     */
    private fun isSameFile(a: Path, b: Path): Boolean {
        return try {
            Files.isSameFile(a, b)
        } catch (e: Exception) {
            false
        }
    }
}

/** Thread-safe holder for the exit callback. */
private class ExitCallbackHolder {
    @Volatile
    var callback: ((Throwable?) -> Unit)? = null
}
