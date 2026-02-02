package io.runwork.bundle.creator

import io.runwork.bundle.common.Arch
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.manifest.BundleFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BundleManifestBuilderTest {

    private val builder = BundleManifestBuilder()

    @Test
    fun `detectPlatforms returns empty list when no resources directory exists`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            val platforms = builder.detectPlatforms(tempDir)
            assertEquals(emptyList(), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms returns empty list when resources directory has only common folder`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/common").mkdirs()
            File(tempDir, "resources/common/file.txt").writeText("content")

            val platforms = builder.detectPlatforms(tempDir)
            assertEquals(emptyList(), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms detects full platform IDs`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/macos-arm64").mkdirs()
            File(tempDir, "resources/windows-x64").mkdirs()

            val platforms = builder.detectPlatforms(tempDir)
            assertEquals(listOf("macos-arm64", "windows-x64"), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms expands OS-only folder to all architectures`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/macos").mkdirs()

            val platforms = builder.detectPlatforms(tempDir)
            assertEquals(listOf("macos-arm64", "macos-x64"), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms handles mixed full platform IDs and OS-only folders`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/macos-arm64").mkdirs()
            File(tempDir, "resources/windows").mkdirs() // OS-only, should expand to windows-arm64 and windows-x64

            val platforms = builder.detectPlatforms(tempDir)
            assertEquals(listOf("macos-arm64", "windows-arm64", "windows-x64"), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms deduplicates when OS-only and full platform ID overlap`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/macos").mkdirs() // Expands to macos-arm64 and macos-x64
            File(tempDir, "resources/macos-arm64").mkdirs() // Explicit macos-arm64

            val platforms = builder.detectPlatforms(tempDir)
            // Should deduplicate and contain only unique platforms
            assertEquals(listOf("macos-arm64", "macos-x64"), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms ignores unknown folders`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/unknown-folder").mkdirs()
            File(tempDir, "resources/macos-arm64").mkdirs()

            val platforms = builder.detectPlatforms(tempDir)
            assertEquals(listOf("macos-arm64"), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms returns sorted list`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/windows-x64").mkdirs()
            File(tempDir, "resources/linux-arm64").mkdirs()
            File(tempDir, "resources/macos-arm64").mkdirs()

            val platforms = builder.detectPlatforms(tempDir)
            assertEquals(listOf("linux-arm64", "macos-arm64", "windows-x64"), platforms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `detectPlatforms expands all supported OS-only folders`() {
        val tempDir = Files.createTempDirectory("builder-test").toFile()
        try {
            File(tempDir, "resources/linux").mkdirs()
            File(tempDir, "resources/macos").mkdirs()
            File(tempDir, "resources/windows").mkdirs()

            val platforms = builder.detectPlatforms(tempDir)
            // Each OS should expand to both arm64 and x64
            assertTrue(platforms.contains("linux-arm64"))
            assertTrue(platforms.contains("linux-x64"))
            assertTrue(platforms.contains("macos-arm64"))
            assertTrue(platforms.contains("macos-x64"))
            assertTrue(platforms.contains("windows-arm64"))
            assertTrue(platforms.contains("windows-x64"))
            assertEquals(6, platforms.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Content fingerprint and deduplication tests

    @Test
    fun `computeContentFingerprint returns same fingerprint for same files`() {
        val files = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
            BundleFile("lib/util.jar", "sha256:def456", 500, null, null),
        )

        val fingerprint1 = builder.computeContentFingerprint(files)
        val fingerprint2 = builder.computeContentFingerprint(files)

        assertEquals(fingerprint1, fingerprint2)
        assertEquals(8, fingerprint1.length)
    }

    @Test
    fun `computeContentFingerprint returns same fingerprint regardless of file order`() {
        val files1 = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
            BundleFile("lib/util.jar", "sha256:def456", 500, null, null),
        )
        val files2 = listOf(
            BundleFile("lib/util.jar", "sha256:def456", 500, null, null),
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
        )

        val fingerprint1 = builder.computeContentFingerprint(files1)
        val fingerprint2 = builder.computeContentFingerprint(files2)

        assertEquals(fingerprint1, fingerprint2)
    }

    @Test
    fun `computeContentFingerprint returns different fingerprint for different files`() {
        val files1 = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
        )
        val files2 = listOf(
            BundleFile("lib/app.jar", "sha256:xyz789", 1000, null, null),
        )

        val fingerprint1 = builder.computeContentFingerprint(files1)
        val fingerprint2 = builder.computeContentFingerprint(files2)

        assertNotEquals(fingerprint1, fingerprint2)
    }

    @Test
    fun `groupPlatformsByContent groups platforms with identical files`() {
        // Universal file only - all platforms should have the same content
        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
        )
        val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")

        val groups = builder.groupPlatformsByContent(bundleFiles, targetPlatforms)

        // All platforms should be in one group since they have the same files
        assertEquals(1, groups.size)
        assertEquals(4, groups.values.first().size)
    }

    @Test
    fun `groupPlatformsByContent separates platforms with different files`() {
        // macOS-arm64 gets a unique native lib, others get universal only
        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
            BundleFile("resources/macos-arm64/native.dylib", "sha256:native123", 5000, Os.MACOS, Arch.ARM64),
        )
        val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")

        val groups = builder.groupPlatformsByContent(bundleFiles, targetPlatforms)

        // Should have 2 groups: one for macos-arm64 (with native lib), one for others
        assertEquals(2, groups.size)

        // Find the macos-arm64 group (should have 1 platform)
        val macosArm64Group = groups.values.find { it.contains("macos-arm64") }!!
        assertEquals(listOf("macos-arm64"), macosArm64Group)

        // Find the others group (should have 3 platforms)
        val othersGroup = groups.values.find { !it.contains("macos-arm64") }!!
        assertEquals(3, othersGroup.size)
        assertTrue(othersGroup.containsAll(listOf("macos-x64", "windows-x64", "linux-x64")))
    }

    @Test
    fun `groupPlatformsByContent handles OS-specific files`() {
        // macOS-specific file (applies to both arm64 and x64)
        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
            BundleFile("resources/macos/config.plist", "sha256:macos123", 500, Os.MACOS, null),
        )
        val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")

        val groups = builder.groupPlatformsByContent(bundleFiles, targetPlatforms)

        // Should have 2 groups: macOS platforms together, others together
        assertEquals(2, groups.size)

        // Find the macOS group
        val macosGroup = groups.values.find { it.contains("macos-arm64") }!!
        assertEquals(2, macosGroup.size)
        assertTrue(macosGroup.containsAll(listOf("macos-arm64", "macos-x64")))

        // Find the others group
        val othersGroup = groups.values.find { !it.contains("macos-arm64") }!!
        assertEquals(2, othersGroup.size)
        assertTrue(othersGroup.containsAll(listOf("windows-x64", "linux-x64")))
    }

    @Test
    fun `groupPlatformsByContent handles complex multi-platform scenario`() {
        // Complex scenario:
        // - Universal: app.jar
        // - macOS arm64 only: native-arm64.dylib
        // - macOS x64 only: native-x64.dylib
        // - Windows (both archs): windows.dll
        val bundleFiles = listOf(
            BundleFile("lib/app.jar", "sha256:abc123", 1000, null, null),
            BundleFile("resources/macos-arm64/native.dylib", "sha256:arm64dylib", 5000, Os.MACOS, Arch.ARM64),
            BundleFile("resources/macos-x64/native.dylib", "sha256:x64dylib", 5000, Os.MACOS, Arch.X64),
            BundleFile("resources/windows/native.dll", "sha256:windowsdll", 5000, Os.WINDOWS, null),
        )
        val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-arm64", "windows-x64", "linux-x64")

        val groups = builder.groupPlatformsByContent(bundleFiles, targetPlatforms)

        // Should have 4 groups:
        // 1. macos-arm64 (app.jar + arm64 dylib)
        // 2. macos-x64 (app.jar + x64 dylib)
        // 3. windows-arm64 + windows-x64 (app.jar + windows dll)
        // 4. linux-x64 (app.jar only)
        assertEquals(4, groups.size)

        // Verify each platform is in exactly one group
        val allPlatforms = groups.values.flatten()
        assertEquals(5, allPlatforms.size)
        assertTrue(allPlatforms.containsAll(targetPlatforms))
    }
}
