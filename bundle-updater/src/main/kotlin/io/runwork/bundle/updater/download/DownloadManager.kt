package io.runwork.bundle.updater.download

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.storage.ContentAddressableStore
import io.runwork.bundle.updater.result.DownloadException
import io.runwork.bundle.updater.result.DownloadResult
import io.runwork.bundle.updater.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages bundle downloads with OkHttp for HTTP/HTTPS URLs and direct file I/O for file:// URLs.
 *
 * Features:
 * - Full bundle ZIP or incremental file downloads
 * - Byte-level progress reporting
 * - Resume support via existing file hash checking
 * - Automatic hash verification after download
 * - Support for file:// URLs for local testing
 * - Multi-platform support with per-platform bundle downloads
 */
class DownloadManager(
    private val baseUrl: String,
    private val storageManager: StorageManager,
    private val platform: Platform,
) : Closeable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(60))
        .writeTimeout(Duration.ofSeconds(60))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val contentStore: ContentAddressableStore get() = storageManager.contentStore

    private fun isFileUrl(url: String): Boolean = url.startsWith("file://")

    /**
     * Fetch the latest manifest from the server.
     *
     * @return The parsed bundle manifest
     * @throws DownloadException if the fetch fails
     */
    suspend fun fetchManifest(): BundleManifest {
        val url = "$baseUrl/manifest.json"
        return if (isFileUrl(url)) {
            fetchManifestFromFile(url)
        } else {
            fetchManifestFromHttp(url)
        }
    }

    private suspend fun fetchManifestFromFile(url: String): BundleManifest = withContext(Dispatchers.IO) {
        val path = try {
            Paths.get(URI(url))
        } catch (e: Exception) {
            throw DownloadException("Invalid file URL: $url", e)
        }

        if (!Files.exists(path)) {
            throw DownloadException("File not found: $path")
        }

        val body = try {
            Files.readString(path)
        } catch (e: IOException) {
            throw DownloadException("Failed to read file: $path", e)
        }

        try {
            json.decodeFromString<BundleManifest>(body)
        } catch (e: Exception) {
            throw DownloadException("Failed to parse manifest", e)
        }
    }

    private suspend fun fetchManifestFromHttp(url: String): BundleManifest {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw DownloadException("Failed to connect to server", e)
        }

        return response.use {
            if (!it.isSuccessful) {
                throw DownloadException("Failed to fetch manifest: HTTP ${it.code}")
            }

            val body = withContext(Dispatchers.IO) {
                it.body?.string()
            } ?: throw DownloadException("Empty response from server")

            try {
                json.decodeFromString<BundleManifest>(body)
            } catch (e: Exception) {
                throw DownloadException("Failed to parse manifest", e)
            }
        }
    }

    /**
     * Await an OkHttp call using coroutines.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }

        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                }
            }
        })
    }

    /**
     * Download a bundle using the optimal strategy.
     *
     * @param manifest The manifest describing the bundle to download
     * @param progressCallback Called with progress updates
     * @return Download result (success, failure, or cancelled)
     */
    suspend fun downloadBundle(
        manifest: BundleManifest,
        progressCallback: suspend (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        when (val strategy = UpdateDecider.decide(manifest, platform, contentStore)) {
            is DownloadStrategy.NoDownloadNeeded -> {
                DownloadResult.Success(manifest.buildNumber)
            }

            is DownloadStrategy.FullBundle -> {
                downloadFullBundle(manifest, strategy, progressCallback)
            }

            is DownloadStrategy.Incremental -> {
                downloadIncremental(manifest, strategy, progressCallback)
            }
        }
    }

    private suspend fun downloadFullBundle(
        manifest: BundleManifest,
        strategy: DownloadStrategy.FullBundle,
        progressCallback: suspend (DownloadProgress) -> Unit
    ): DownloadResult {
        val tempZip = storageManager.createTempFile("bundle")

        return try {
            // Get platform-specific bundle URL
            val bundleZipPath = manifest.bundleZipForPlatform(platform)
                ?: throw DownloadException("No bundle zip available for platform: $platform")

            // Resolve relative URL from manifest location
            val bundleUrl = resolveRelativeUrl(baseUrl, bundleZipPath)

            downloadFile(
                url = bundleUrl,
                destPath = tempZip,
                expectedSize = strategy.totalSize,
                progressCallback = { downloaded, total ->
                    progressCallback(
                        DownloadProgress(
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            currentFile = bundleZipPath,
                            filesCompleted = 0,
                            totalFiles = 1
                        )
                    )
                }
            )

            // Extract and store files from ZIP (only files for this platform)
            extractAndStoreBundle(tempZip, manifest)

            DownloadResult.Success(manifest.buildNumber)
        } catch (e: DownloadException) {
            DownloadResult.Failure(e.message ?: "Download failed", e)
        } catch (e: Exception) {
            DownloadResult.Failure("Unexpected error: ${e.message}", e)
        } finally {
            Files.deleteIfExists(tempZip)
        }
    }

    /**
     * Resolve a relative URL from the manifest base URL.
     *
     * Example:
     * - baseUrl: "https://cdn.example.com/v1"
     * - relativePath: "bundle-macos-arm64.zip"
     * - Result: "https://cdn.example.com/v1/bundle-macos-arm64.zip"
     */
    private fun resolveRelativeUrl(baseUrl: String, relativePath: String): String {
        // Remove trailing slash from base if present
        val base = baseUrl.trimEnd('/')
        return "$base/$relativePath"
    }

    private suspend fun downloadIncremental(
        manifest: BundleManifest,
        strategy: DownloadStrategy.Incremental,
        progressCallback: suspend (DownloadProgress) -> Unit
    ): DownloadResult {
        var totalDownloaded = 0L
        val totalBytes = strategy.totalSize

        for ((index, file) in strategy.files.withIndex()) {
            if (!coroutineContext.isActive) {
                return DownloadResult.Cancelled
            }

            val tempFile = storageManager.createTempFile("file")

            try {
                val hash = file.hash.removePrefix("sha256:")
                downloadFile(
                    url = "$baseUrl/files/$hash",
                    destPath = tempFile,
                    expectedSize = file.size,
                    progressCallback = { downloaded, _ ->
                        progressCallback(
                            DownloadProgress(
                                bytesDownloaded = totalDownloaded + downloaded,
                                totalBytes = totalBytes,
                                currentFile = file.path,
                                filesCompleted = index,
                                totalFiles = strategy.files.size
                            )
                        )
                    }
                )

                // Verify and store the file
                val stored = contentStore.storeWithHash(tempFile, file.hash)
                if (!stored) {
                    return DownloadResult.Failure("Hash mismatch for ${file.path}")
                }

                totalDownloaded += file.size
            } catch (e: DownloadException) {
                Files.deleteIfExists(tempFile)
                return DownloadResult.Failure("Failed to download ${file.path}: ${e.message}", e)
            } catch (e: Exception) {
                Files.deleteIfExists(tempFile)
                return DownloadResult.Failure("Unexpected error downloading ${file.path}: ${e.message}", e)
            }
        }

        return DownloadResult.Success(manifest.buildNumber)
    }

    private suspend fun downloadFile(
        url: String,
        destPath: Path,
        expectedSize: Long,
        progressCallback: suspend (Long, Long) -> Unit
    ) {
        if (isFileUrl(url)) {
            downloadFileFromFilesystem(url, destPath, expectedSize, progressCallback)
        } else {
            downloadFileFromHttp(url, destPath, expectedSize, progressCallback)
        }
    }

    private suspend fun downloadFileFromFilesystem(
        url: String,
        destPath: Path,
        expectedSize: Long,
        progressCallback: suspend (Long, Long) -> Unit
    ) {
        val sourcePath = try {
            Paths.get(URI(url))
        } catch (e: Exception) {
            throw DownloadException("Invalid file URL: $url", e)
        }

        if (!Files.exists(sourcePath)) {
            throw DownloadException("File not found: $sourcePath")
        }

        Files.newInputStream(sourcePath).use { input ->
            Files.newOutputStream(
                destPath,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { output ->
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L
                var bytesRead: Int

                while (input.read(buffer).also { b -> bytesRead = b } != -1) {
                    if (!coroutineContext.isActive) {
                        throw DownloadException("Download cancelled")
                    }

                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    progressCallback(totalBytesRead, expectedSize)
                }
            }
        }
    }

    private suspend fun downloadFileFromHttp(
        url: String,
        destPath: Path,
        expectedSize: Long,
        progressCallback: suspend (Long, Long) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw DownloadException("Failed to connect: $url", e)
        }

        response.use {
            if (!it.isSuccessful) {
                throw DownloadException("HTTP ${it.code} for $url")
            }

            val body = it.body ?: throw DownloadException("Empty response for $url")

            withContext(Dispatchers.IO) {
                Files.newOutputStream(
                    destPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var totalBytesRead = 0L
                        var bytesRead: Int

                        while (input.read(buffer).also { b -> bytesRead = b } != -1) {
                            if (!coroutineContext.isActive) {
                                throw DownloadException("Download cancelled")
                            }

                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            progressCallback(totalBytesRead, expectedSize)
                        }
                    }
                }
            }
        }
    }

    private suspend fun extractAndStoreBundle(
        zipPath: Path,
        manifest: BundleManifest
    ) {
        // Create a map of path -> expected hash for files applicable to this platform
        val platformFiles = manifest.filesForPlatform(platform)
        val expectedHashes = platformFiles.associate { it.path to it.hash }

        ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val expectedHash = expectedHashes[entry.name]
                    if (expectedHash != null) {
                        // Write to temp file
                        val tempFile = storageManager.createTempFile("extract")
                        try {
                            Files.newOutputStream(tempFile).use { output ->
                                zip.copyTo(output)
                            }

                            // Verify and store
                            val stored = contentStore.storeWithHash(tempFile, expectedHash)
                            if (!stored) {
                                throw DownloadException("Hash mismatch for ${entry.name} in bundle.zip")
                            }
                        } finally {
                            // Clean up temp file (storeWithHash moves it on success, so this handles failures)
                            Files.deleteIfExists(tempFile)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Close the download manager and release resources.
     */
    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
