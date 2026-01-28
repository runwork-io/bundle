package io.runwork.bundle.testing

import io.runwork.bundle.TestFixtures
import io.runwork.bundle.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Client state simulator for integration testing.
 *
 * Sets up client-side storage state to simulate different scenarios:
 * - Fresh install (no bundle installed)
 * - Existing version installed
 * - Corrupted files
 * - Multiple versions
 */
class TestBundleClient private constructor(
    val appDataDir: Path,
    val storageManager: StorageManager
) {
    companion object {
        private val json = Json { prettyPrint = true }

        /**
         * Create client with initial state.
         */
        suspend fun create(
            appDataDir: Path,
            block: suspend TestBundleClientBuilder.() -> Unit
        ): TestBundleClient {
            Files.createDirectories(appDataDir)
            val storageManager = StorageManager(appDataDir)
            val client = TestBundleClient(appDataDir, storageManager)
            val builder = TestBundleClientBuilder(appDataDir, storageManager)
            builder.block()
            return client
        }

        /**
         * Create empty client (no installed bundles).
         */
        fun empty(appDataDir: Path): TestBundleClient {
            Files.createDirectories(appDataDir)
            val storageManager = StorageManager(appDataDir)
            return TestBundleClient(appDataDir, storageManager)
        }
    }

    private val versionsDir = appDataDir.resolve("versions")
    private val manifestPath = appDataDir.resolve("manifest.json")

    /**
     * Get current installed build number.
     */
    suspend fun currentBuildNumber(): Long? {
        return storageManager.getCurrentVersion()
    }

    /**
     * Check if file exists in version directory.
     */
    suspend fun hasFile(buildNumber: Long, path: String): Boolean = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        Files.exists(versionDir.resolve(path))
    }

    /**
     * Corrupt a file in version directory (write garbage).
     */
    suspend fun corruptFile(buildNumber: Long, path: String) = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        val filePath = versionDir.resolve(path)
        if (Files.exists(filePath)) {
            Files.writeString(filePath, "CORRUPTED DATA - INVALID CONTENT")
        }
    }

    /**
     * Delete a file from version directory.
     */
    suspend fun deleteFile(buildNumber: Long, path: String) = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        val filePath = versionDir.resolve(path)
        Files.deleteIfExists(filePath)
    }

    /**
     * Corrupt the stored manifest signature.
     */
    suspend fun corruptManifest() = withContext(Dispatchers.IO) {
        if (Files.exists(manifestPath)) {
            val content = Files.readString(manifestPath)
            val corrupted = content.replace(
                Regex(""""signature"\s*:\s*"[^"]*""""),
                """"signature": "ed25519:invalidCorruptedSignature""""
            )
            Files.writeString(manifestPath, corrupted)
        }
    }

    /**
     * Get file content from version directory.
     */
    suspend fun readFile(buildNumber: Long, path: String): ByteArray? = withContext(Dispatchers.IO) {
        val versionDir = versionsDir.resolve(buildNumber.toString())
        val filePath = versionDir.resolve(path)
        if (Files.exists(filePath)) {
            Files.readAllBytes(filePath)
        } else {
            null
        }
    }
}

/**
 * Builder for setting up TestBundleClient initial state.
 */
class TestBundleClientBuilder(
    private val appDataDir: Path,
    private val storageManager: StorageManager
) {
    private val json = Json { prettyPrint = true }

    /**
     * Install bundle as current version.
     *
     * This stores all files in CAS, prepares the version directory,
     * saves the manifest, and sets it as the current version.
     */
    suspend fun install(bundle: TestBundle) {
        // Store all files in CAS
        addToCas(bundle)

        // Prepare version directory (creates hard links from CAS)
        storageManager.prepareVersion(bundle.manifest)

        // Save manifest
        storageManager.saveManifest(json.encodeToString(bundle.manifest))

        // Set as current version
        storageManager.setCurrentVersion(bundle.manifest.buildNumber)
    }

    /**
     * Install bundle as a specific version (optionally set as current).
     */
    suspend fun installVersion(bundle: TestBundle, setCurrent: Boolean = false) {
        // Store all files in CAS
        addToCas(bundle)

        // Prepare version directory
        storageManager.prepareVersion(bundle.manifest)

        if (setCurrent) {
            // Save manifest
            storageManager.saveManifest(json.encodeToString(bundle.manifest))

            // Set as current version
            storageManager.setCurrentVersion(bundle.manifest.buildNumber)
        }
    }

    /**
     * Add bundle files to CAS without installing version.
     *
     * This is useful for testing incremental updates where files
     * exist in CAS but no version is installed.
     */
    suspend fun addToCas(bundle: TestBundle) {
        for ((hash, content) in bundle.files) {
            addToCas(hash, content)
        }
    }

    /**
     * Add specific file contents to CAS.
     *
     * @param files Map of hash (with sha256: prefix) to content
     */
    suspend fun addToCas(files: Map<String, ByteArray>) {
        for ((hash, content) in files) {
            addToCas(hash, content)
        }
    }

    /**
     * Add a single file to CAS.
     */
    private suspend fun addToCas(hash: String, content: ByteArray) {
        // Create temp file and store in CAS
        val tempFile = withContext(Dispatchers.IO) {
            val hashWithoutPrefix = hash.removePrefix("sha256:")
            val tempPath = appDataDir.resolve("temp-$hashWithoutPrefix")
            Files.createDirectories(tempPath.parent)
            Files.write(tempPath, content)
            tempPath
        }

        try {
            storageManager.contentStore.store(tempFile)
        } finally {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(tempFile)
            }
        }
    }
}
