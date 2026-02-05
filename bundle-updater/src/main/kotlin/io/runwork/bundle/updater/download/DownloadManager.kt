package io.runwork.bundle.updater.download

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.storage.ContentAddressableStore
import io.runwork.bundle.updater.result.DownloadException
import io.runwork.bundle.updater.result.DownloadResult
import io.runwork.bundle.updater.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.FileSystem
import okio.ForwardingSource
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A fetched manifest preserving the raw JSON string.
 *
 * The raw JSON is preserved for forward-compatible signature verification:
 * verifying against the original bytes avoids losing unknown fields that
 * would be dropped during deserialization into [BundleManifest].
 */
internal class FetchedManifest(
    val rawJson: String,
    val manifest: BundleManifest,
) {
    companion object {
        fun fromRawJson(rawJson: String): FetchedManifest {
            val manifest = try {
                BundleJson.decodingJson.decodeFromString<BundleManifest>(rawJson)
            } catch (e: Exception) {
                throw DownloadException("Failed to parse manifest", e)
            }
            return FetchedManifest(rawJson, manifest)
        }
    }
}

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

    val contentStore: ContentAddressableStore get() = storageManager.contentStore

    private fun isFileUrl(url: String): Boolean = url.startsWith("file://")

    /**
     * Fetch the latest manifest from the server.
     *
     * @return The parsed bundle manifest alongside the raw JSON
     * @throws DownloadException if the fetch fails
     */
    internal suspend fun fetchManifest(): FetchedManifest {
        val url = "$baseUrl/manifest.json"
        return if (isFileUrl(url)) {
            fetchManifestFromFile(url)
        } else {
            fetchManifestFromHttp(url)
        }
    }

    private suspend fun fetchManifestFromFile(url: String): FetchedManifest = withContext(Dispatchers.IO) {
        val path = try {
            Paths.get(URI(url))
        } catch (e: Exception) {
            throw DownloadException("Invalid file URL: $url", e)
        }

        if (!Files.exists(path)) {
            throw DownloadException("File not found: $path")
        }

        val body = try {
            FileSystem.SYSTEM.read(path.toOkioPath()) { readUtf8() }
        } catch (e: IOException) {
            throw DownloadException("Failed to read file: $path", e)
        }

        FetchedManifest.fromRawJson(body)
    }

    private suspend fun fetchManifestFromHttp(url: String): FetchedManifest {
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

            FetchedManifest.fromRawJson(body)
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
        progressCallback: (DownloadProgress) -> Unit
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
        progressCallback: (DownloadProgress) -> Unit
    ): DownloadResult {
        val tempZip = storageManager.createTempFile("bundle")

        return try {
            // Get platform-specific bundle URL
            val bundleZipPath = manifest.zipForPlatform(platform)
                ?: throw DownloadException("No bundle zip available for platform: $platform")

            // Resolve relative URL from manifest location
            val bundleUrl = resolveRelativeUrl(baseUrl, bundleZipPath)

            downloadFile(
                url = bundleUrl,
                destPath = tempZip,
                expectedSize = strategy.totalSize,
            ) { bytesRead, totalBytes ->
                progressCallback(
                    DownloadProgress(
                        bytesDownloaded = bytesRead,
                        totalBytes = totalBytes,
                        currentFile = bundleZipPath,
                        filesCompleted = 0,
                        totalFiles = 1
                    )
                )
            }

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
        progressCallback: (DownloadProgress) -> Unit
    ): DownloadResult {
        var totalDownloaded = 0L
        val totalBytes = strategy.totalSize

        for ((index, file) in strategy.files.withIndex()) {
            if (!coroutineContext.isActive) {
                return DownloadResult.Cancelled
            }

            val tempFile = storageManager.createTempFile("file")

            try {
                val hash = file.hash.hex
                val baseDownloaded = totalDownloaded
                downloadFile(
                    url = "$baseUrl/files/$hash",
                    destPath = tempFile,
                    expectedSize = file.size,
                ) { bytesRead, _ ->
                    progressCallback(
                        DownloadProgress(
                            bytesDownloaded = baseDownloaded + bytesRead,
                            totalBytes = totalBytes,
                            currentFile = file.path,
                            filesCompleted = index,
                            totalFiles = strategy.files.size
                        )
                    )
                }

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
        expectedSize: Long = -1L,
        progressCallback: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
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
        expectedSize: Long = -1L,
        progressCallback: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val sourcePath = try {
            Paths.get(URI(url))
        } catch (e: Exception) {
            throw DownloadException("Invalid file URL: $url", e)
        }

        if (!Files.exists(sourcePath)) {
            throw DownloadException("File not found: $sourcePath")
        }

        val job = coroutineContext[Job]

        withContext(Dispatchers.IO) {
            FileSystem.SYSTEM.source(sourcePath.toOkioPath()).let { source ->
                when {
                    progressCallback != null -> ProgressSource(source, expectedSize, progressCallback, job)
                    job != null -> CancellableSource(source, job)
                    else -> source
                }
            }.use { source ->
                FileSystem.SYSTEM.sink(destPath.toOkioPath()).buffer().use { sink ->
                    sink.writeAll(source)
                }
            }
        }
    }

    private suspend fun downloadFileFromHttp(
        url: String,
        destPath: Path,
        expectedSize: Long = -1L,
        progressCallback: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
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

        val job = coroutineContext[Job]

        response.use {
            if (!it.isSuccessful) {
                throw DownloadException("HTTP ${it.code} for $url")
            }

            val body = it.body ?: throw DownloadException("Empty response for $url")

            withContext(Dispatchers.IO) {
                body.source().let { source ->
                    when {
                        progressCallback != null -> ProgressSource(source, expectedSize, progressCallback, job)
                        job != null -> CancellableSource(source, job)
                        else -> source
                    }
                }.use { source ->
                    FileSystem.SYSTEM.sink(destPath.toOkioPath()).buffer().use { sink ->
                        sink.writeAll(source)
                    }
                }
            }
        }
    }

    private suspend fun extractAndStoreBundle(
        zipPath: Path,
        manifest: BundleManifest
    ) {
        // Build a set of expected hash hexes for files applicable to this platform
        val platformFiles = manifest.filesForPlatform(platform)
        val expectedHashHexes = platformFiles.map { it.hash.hex }.toSet()

        ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val hashHex = entry.name
                    if (hashHex in expectedHashHexes) {
                        val expectedHash = BundleFileHash("sha256", hashHex)
                        // Write to temp file
                        val tempFile = storageManager.createTempFile("extract")
                        try {
                            FileSystem.SYSTEM.sink(tempFile.toOkioPath()).buffer().use { sink ->
                                sink.writeAll(zip.source())
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

    private class ProgressSource(
        source: Source,
        private val expectedSize: Long,
        private val onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        private val job: Job? = null,
    ) : ForwardingSource(source) {
        private var bytesRead = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (job?.isActive == false) throw IOException("Download cancelled")
            val read = super.read(sink, byteCount)
            if (read != -1L) {
                bytesRead += read
                onProgress(bytesRead, expectedSize)
            }
            return read
        }
    }

    private class CancellableSource(
        source: Source,
        private val job: Job,
    ) : ForwardingSource(source) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (!job.isActive) throw IOException("Download cancelled")
            return super.read(sink, byteCount)
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
