package io.runwork.bundle.creator

import io.runwork.bundle.common.Arch
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.manifest.BundleFile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundlePackagerTest {

    private lateinit var tempDir: File
    private lateinit var inputDir: File
    private lateinit var outputDir: File
    private val packager = BundlePackager()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("packager-test").toFile()
        inputDir = File(tempDir, "input").also { it.mkdirs() }
        outputDir = File(tempDir, "output").also { it.mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `packageBundle deduplicates identical platform content`() {
        // Create universal files only
        File(inputDir, "lib").mkdirs()
        File(inputDir, "lib/app.jar").writeText("jar content")

        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 11, null, null),
        )

        val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")

        val result = packager.packageBundle(inputDir, outputDir, bundleFiles, targetPlatforms)

        // All platforms should point to the same zip file
        val uniqueZips = result.values.map { it.zip }.toSet()
        assertEquals(1, uniqueZips.size, "All platforms with same content should share one zip")

        // Verify all platforms map to the same zip
        val sharedZip = uniqueZips.first()
        for (platform in targetPlatforms) {
            assertEquals(sharedZip, result[platform]!!.zip)
        }

        // Verify the zip file exists
        assertTrue(File(outputDir, sharedZip).exists())

        // Verify only one zip file was created (not 4)
        val zipFiles = outputDir.listFiles()?.filter { it.name.endsWith(".zip") } ?: listOf()
        assertEquals(1, zipFiles.size)
    }

    @Test
    fun `packageBundle creates separate zips for platforms with different content`() {
        // Create files
        File(inputDir, "lib").mkdirs()
        File(inputDir, "lib/app.jar").writeText("jar content")
        File(inputDir, "resources/macos-arm64").mkdirs()
        File(inputDir, "resources/macos-arm64/native.dylib").writeText("native arm64")

        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 11, null, null),
            BundleFile("resources/macos-arm64/native.dylib", "sha256:native123", 12, Os.MACOS, Arch.ARM64),
        )

        val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")

        val result = packager.packageBundle(inputDir, outputDir, bundleFiles, targetPlatforms)

        // macos-arm64 should have its own zip (has native dylib)
        // Other platforms should share a zip (universal only)
        val uniqueZips = result.values.map { it.zip }.toSet()
        assertEquals(2, uniqueZips.size, "Should have 2 unique zips")

        // Verify macos-arm64 has different zip than others
        val macosArm64Zip = result["macos-arm64"]!!.zip
        val macosX64Zip = result["macos-x64"]!!.zip
        val windowsZip = result["windows-x64"]!!.zip
        val linuxZip = result["linux-x64"]!!.zip

        assertTrue(macosArm64Zip != macosX64Zip, "macos-arm64 should have different zip than macos-x64")
        assertEquals(macosX64Zip, windowsZip, "macos-x64 and windows-x64 should share same zip")
        assertEquals(windowsZip, linuxZip, "windows-x64 and linux-x64 should share same zip")

        // Verify 2 zip files were created
        val zipFiles = outputDir.listFiles()?.filter { it.name.endsWith(".zip") } ?: listOf()
        assertEquals(2, zipFiles.size)
    }

    @Test
    fun `packageBundle creates content-addressable filenames`() {
        File(inputDir, "lib").mkdirs()
        File(inputDir, "lib/app.jar").writeText("jar content")

        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 11, null, null),
        )

        val targetPlatforms = listOf("macos-arm64")

        val result = packager.packageBundle(inputDir, outputDir, bundleFiles, targetPlatforms)

        // Zip filename should be content-addressable (8-char fingerprint)
        val zipName = result["macos-arm64"]!!.zip
        assertTrue(zipName.startsWith("bundle-"), "Zip name should start with 'bundle-'")
        assertTrue(zipName.endsWith(".zip"), "Zip name should end with '.zip'")

        // Extract fingerprint part
        val fingerprint = zipName.removePrefix("bundle-").removeSuffix(".zip")
        assertEquals(8, fingerprint.length, "Content fingerprint should be 8 characters")

        // Fingerprint should be hex (lowercase)
        assertTrue(fingerprint.all { it in '0'..'9' || it in 'a'..'f' }, "Fingerprint should be hex")
    }

    @Test
    fun `packageBundle creates files directory with content-addressable names`() {
        File(inputDir, "lib").mkdirs()
        File(inputDir, "lib/app.jar").writeText("jar content")
        File(inputDir, "lib/util.jar").writeText("util content")

        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 11, null, null),
            BundleFile("lib/util.jar", "sha256:def456", 12, null, null),
        )

        packager.packageBundle(inputDir, outputDir, bundleFiles, listOf("macos-arm64"))

        // Verify files directory exists
        val filesDir = File(outputDir, "files")
        assertTrue(filesDir.exists())
        assertTrue(filesDir.isDirectory)

        // Verify each file exists with hash name
        assertTrue(File(filesDir, "abc123").exists())
        assertTrue(File(filesDir, "def456").exists())
    }
}
