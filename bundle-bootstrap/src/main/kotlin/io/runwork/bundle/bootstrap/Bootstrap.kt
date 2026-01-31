package io.runwork.bundle.bootstrap

import io.runwork.bundle.bootstrap.loader.BundleClassLoader
import io.runwork.bundle.bootstrap.loader.BundleLoadException
import io.runwork.bundle.bootstrap.loader.LoadedBundle
import io.runwork.bundle.common.BundleLaunchConfig
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.FileType
import io.runwork.bundle.common.storage.ContentAddressableStore
import io.runwork.bundle.common.verification.HashVerifier
import io.runwork.bundle.common.verification.SignatureVerifier
import kotlinx.coroutines.Dispatchers
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
 * 1. [validate] - Validates the bundle without launching (returns [ValidationResult])
 * 2. [launch] - Launches a validated bundle (only call with [ValidationResult.Valid])
 *
 * This separation allows the shell to:
 * - Show appropriate UI based on validation result
 * - Handle missing bundles by calling BundleUpdater.downloadLatest()
 * - Handle shell updates before attempting to launch
 */
class Bootstrap(
    private val config: BootstrapConfig
) {
    private val versionsDir = config.appDataDir.resolve("versions")
    private val manifestPath = config.appDataDir.resolve("manifest.json")
    private val casDir = config.appDataDir.resolve("cas")
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
        onProgress: (BootstrapProgress) -> Unit = {}
    ): ValidationResult {
        onProgress(BootstrapProgress.LoadingManifest)

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
            return ValidationResult.NoBundleExists
        }

        val manifest = try {
            json.decodeFromString<BundleManifest>(manifestJson)
        } catch (e: Exception) {
            return ValidationResult.Failed("Failed to parse manifest: ${e.message}")
        }

        onProgress(BootstrapProgress.VerifyingSignature)

        // Verify signature
        if (!signatureVerifier.verifyManifest(manifest)) {
            return ValidationResult.Failed("Manifest signature verification failed")
        }

        // Check platform
        if (manifest.platform != config.platform) {
            return ValidationResult.Failed(
                "Platform mismatch: manifest is for ${manifest.platform}, but running on ${config.platform}"
            )
        }

        // Check shell version
        if (config.shellVersion < manifest.minimumShellVersion) {
            return ValidationResult.ShellUpdateRequired(
                currentVersion = config.shellVersion,
                requiredVersion = manifest.minimumShellVersion,
                updateUrl = manifest.rootAppUpdateUrl,
            )
        }

        // Check version directory exists and is complete
        val versionDir = versionsDir.resolve(manifest.buildNumber.toString())
        val completeMarker = versionDir.resolve(".complete")

        if (!withContext(Dispatchers.IO) { Files.exists(completeMarker) }) {
            // Version directory incomplete or missing
            // This could happen if download was interrupted
            return ValidationResult.NoBundleExists
        }

        // Verify all files concurrently
        val totalFiles = manifest.files.size
        onProgress(BootstrapProgress.VerifyingFiles(0, totalFiles))

        val filesToVerify = manifest.files.map { file ->
            versionDir.resolve(file.path) to file.hash
        }

        val results = HashVerifier.verifyFilesConcurrently(filesToVerify, parallelism = 5)

        val failures = results.filterNot { it.success }.map { result ->
            VerificationFailure(
                path = versionDir.relativize(result.path).toString(),
                expectedHash = result.expectedHash,
                actualHash = result.actualHash,
                reason = if (result.actualHash == null) "File missing" else "Hash mismatch"
            )
        }

        onProgress(BootstrapProgress.VerifyingFiles(totalFiles, totalFiles))

        if (failures.isNotEmpty()) {
            return ValidationResult.Failed(
                reason = "File verification failed for ${failures.size} file(s)",
                failures = failures,
            )
        }

        onProgress(BootstrapProgress.Complete)

        return ValidationResult.Valid(
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
     * @param validation A [ValidationResult.Valid] from a successful [validate] call
     * @return LoadedBundle containing the classloader and control methods
     * @throws BundleLoadException if the bundle fails to load
     */
    fun launch(validation: ValidationResult.Valid): LoadedBundle {
        val manifest = validation.manifest
        val versionPath = validation.versionPath

        // Collect all JARs
        val jarUrls = manifest.files
            .filter { it.type == FileType.JAR }
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
            throw BundleLoadException("Main class not found: $mainClassName", e)
        }

        // Find the main method
        val mainMethod = try {
            entryClass.getMethod("main", Array<String>::class.java)
        } catch (e: NoSuchMethodException) {
            throw BundleLoadException(
                "Main class $mainClassName must have a static main(Array<String>) method",
                e
            )
        }

        // Validate that main is static
        if (!Modifier.isStatic(mainMethod.modifiers)) {
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
            baseUrl = config.baseUrl,
            publicKey = config.publicKey,
            platform = config.platform,
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
}

/** Thread-safe holder for the exit callback. */
private class ExitCallbackHolder {
    @Volatile
    var callback: ((Throwable?) -> Unit)? = null
}
