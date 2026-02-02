package io.runwork.bundle.resources

import io.runwork.bundle.common.BundleLaunchConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleResourcesTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        BundleResources.reset()
    }

    @AfterEach
    fun tearDown() {
        BundleResources.reset()
    }

    private fun createConfig(
        platform: String = "macos-arm64",
        buildNumber: Long = 100L,
        bundleSubdirectory: String = "bundle",
    ): BundleLaunchConfig {
        return BundleLaunchConfig(
            appDataDir = tempDir.toString(),
            bundleSubdirectory = bundleSubdirectory,
            baseUrl = "https://example.com",
            publicKey = "test-public-key",
            platform = platform,
            shellVersion = 1,
            currentBuildNumber = buildNumber,
        )
    }

    private fun createResource(vararg pathParts: String, content: String = "test content"): Path {
        val config = BundleResources.run {
            if (isInitialized) {
                BundleLaunchConfig(
                    appDataDir = tempDir.toString(),
                    bundleSubdirectory = "bundle",
                    baseUrl = "https://example.com",
                    publicKey = "test-public-key",
                    platform = platform.toString(),
                    shellVersion = 1,
                    currentBuildNumber = 100L,
                )
            } else {
                createConfig()
            }
        }

        val bundleDir = if (config.bundleSubdirectory.isEmpty()) {
            tempDir
        } else {
            tempDir.resolve(config.bundleSubdirectory)
        }
        val versionDir = bundleDir.resolve("versions").resolve(config.currentBuildNumber.toString())
        val resourcesDir = versionDir.resolve("resources")

        var path = resourcesDir
        for (part in pathParts) {
            path = path.resolve(part)
        }
        path.parent.createDirectories()
        path.writeText(content)
        return path
    }

    @Nested
    inner class Initialization {
        @Test
        fun `init succeeds with valid config`() {
            val config = createConfig()
            BundleResources.init(config)

            assertTrue(BundleResources.isInitialized)
            assertEquals("macos-arm64", BundleResources.platform.toString())
        }

        @Test
        fun `init throws if already initialized`() {
            val config = createConfig()
            BundleResources.init(config)

            assertFailsWith<IllegalStateException> {
                BundleResources.init(config)
            }
        }

        @Test
        fun `isInitialized returns false before init`() {
            assertFalse(BundleResources.isInitialized)
        }

        @Test
        fun `versionDir throws if not initialized`() {
            assertFailsWith<IllegalStateException> {
                BundleResources.versionDir
            }
        }

        @Test
        fun `platform throws if not initialized`() {
            assertFailsWith<IllegalStateException> {
                BundleResources.platform
            }
        }

        @Test
        fun `versionDir is computed correctly`() {
            val config = createConfig(buildNumber = 123L)
            BundleResources.init(config)

            val expected = tempDir.resolve("bundle/versions/123")
            assertEquals(expected, BundleResources.versionDir)
        }

        @Test
        fun `versionDir handles empty bundleSubdirectory`() {
            val config = createConfig(buildNumber = 456L, bundleSubdirectory = "")
            BundleResources.init(config)

            val expected = tempDir.resolve("versions/456")
            assertEquals(expected, BundleResources.versionDir)
        }

        @Test
        fun `reset allows re-initialization`() {
            val config1 = createConfig(platform = "macos-arm64")
            BundleResources.init(config1)
            assertEquals("macos-arm64", BundleResources.platform.toString())

            BundleResources.reset()
            assertFalse(BundleResources.isInitialized)

            val config2 = createConfig(platform = "linux-x64")
            BundleResources.init(config2)
            assertEquals("linux-x64", BundleResources.platform.toString())
        }
    }

    @Nested
    inner class Resolution {
        @BeforeEach
        fun initResources() {
            BundleResources.init(createConfig(platform = "macos-arm64"))
        }

        @Test
        fun `resolve returns null when resource not found`() {
            val result = BundleResources.resolve("nonexistent/file.txt")
            assertNull(result)
        }

        @Test
        fun `resolve finds platform-specific resource`() {
            val platformPath = createResource("macos-arm64", "config.json")

            val result = BundleResources.resolve("config.json")
            assertNotNull(result)
            assertEquals(platformPath, result)
        }

        @Test
        fun `resolve finds os-only resource`() {
            val osPath = createResource("macos", "config.json")

            val result = BundleResources.resolve("config.json")
            assertNotNull(result)
            assertEquals(osPath, result)
        }

        @Test
        fun `resolve finds common resource`() {
            val commonPath = createResource("common", "config.json")

            val result = BundleResources.resolve("config.json")
            assertNotNull(result)
            assertEquals(commonPath, result)
        }

        @Test
        fun `resolve prefers platform-specific over os-only`() {
            val platformPath = createResource("macos-arm64", "config.json", content = "platform")
            createResource("macos", "config.json", content = "os")
            createResource("common", "config.json", content = "common")

            val result = BundleResources.resolve("config.json")
            assertNotNull(result)
            assertEquals(platformPath, result)
        }

        @Test
        fun `resolve prefers os-only over common`() {
            val osPath = createResource("macos", "config.json", content = "os")
            createResource("common", "config.json", content = "common")

            val result = BundleResources.resolve("config.json")
            assertNotNull(result)
            assertEquals(osPath, result)
        }

        @Test
        fun `resolve handles nested paths`() {
            val path = createResource("common", "nested", "deep", "file.txt")

            val result = BundleResources.resolve("nested/deep/file.txt")
            assertNotNull(result)
            assertEquals(path, result)
        }

        @Test
        fun `resolveOrThrow returns path when found`() {
            val path = createResource("common", "exists.txt")

            val result = BundleResources.resolveOrThrow("exists.txt")
            assertEquals(path, result)
        }

        @Test
        fun `resolveOrThrow throws ResourceNotFoundException when not found`() {
            val exception = assertFailsWith<ResourceNotFoundException> {
                BundleResources.resolveOrThrow("nonexistent.txt")
            }

            assertEquals("nonexistent.txt", exception.path)
            assertEquals(3, exception.searchedLocations.size)
            assertTrue(exception.searchedLocations[0].toString().contains("macos-arm64"))
            assertTrue(exception.searchedLocations[1].toString().contains("macos"))
            assertTrue(exception.searchedLocations[2].toString().contains("common"))
        }

        @Test
        fun `resolve throws if not initialized`() {
            BundleResources.reset()

            assertFailsWith<IllegalStateException> {
                BundleResources.resolve("file.txt")
            }
        }

        @Test
        fun `resolveOrThrow throws if not initialized`() {
            BundleResources.reset()

            assertFailsWith<IllegalStateException> {
                BundleResources.resolveOrThrow("file.txt")
            }
        }
    }

    @Nested
    inner class NativeLibrary {
        @Test
        fun `resolveNativeLibrary uses dylib extension on macOS`() {
            BundleResources.init(createConfig(platform = "macos-arm64"))
            val libPath = createResource("macos-arm64", "libwhisper.dylib")

            val result = BundleResources.resolveNativeLibrary("whisper")
            assertNotNull(result)
            assertEquals(libPath, result)
        }

        @Test
        fun `resolveNativeLibrary uses dll extension on Windows`() {
            BundleResources.init(createConfig(platform = "windows-x64"))

            // Create the file in the right location for windows platform
            val bundleDir = tempDir.resolve("bundle")
            val versionDir = bundleDir.resolve("versions/100")
            val resourcesDir = versionDir.resolve("resources")
            val libPath = resourcesDir.resolve("windows-x64/whisper.dll")
            libPath.parent.createDirectories()
            libPath.writeText("dll content")

            val result = BundleResources.resolveNativeLibrary("whisper")
            assertNotNull(result)
            assertEquals(libPath, result)
        }

        @Test
        fun `resolveNativeLibrary uses so extension on Linux`() {
            BundleResources.init(createConfig(platform = "linux-x64"))

            val bundleDir = tempDir.resolve("bundle")
            val versionDir = bundleDir.resolve("versions/100")
            val resourcesDir = versionDir.resolve("resources")
            val libPath = resourcesDir.resolve("linux-x64/libwhisper.so")
            libPath.parent.createDirectories()
            libPath.writeText("so content")

            val result = BundleResources.resolveNativeLibrary("whisper")
            assertNotNull(result)
            assertEquals(libPath, result)
        }

        @Test
        fun `resolveNativeLibrary returns null when not found`() {
            BundleResources.init(createConfig(platform = "macos-arm64"))

            val result = BundleResources.resolveNativeLibrary("nonexistent")
            assertNull(result)
        }

        @Test
        fun `resolveNativeLibrary uses platform priority`() {
            BundleResources.init(createConfig(platform = "macos-arm64"))

            // Create lib in common
            val bundleDir = tempDir.resolve("bundle")
            val versionDir = bundleDir.resolve("versions/100")
            val resourcesDir = versionDir.resolve("resources")
            val commonPath = resourcesDir.resolve("common/libwhisper.dylib")
            commonPath.parent.createDirectories()
            commonPath.writeText("common lib")

            // Create lib in platform-specific
            val platformPath = resourcesDir.resolve("macos-arm64/libwhisper.dylib")
            platformPath.parent.createDirectories()
            platformPath.writeText("platform lib")

            val result = BundleResources.resolveNativeLibrary("whisper")
            assertNotNull(result)
            assertEquals(platformPath, result)
        }

        @Test
        fun `resolveNativeLibrary throws if not initialized`() {
            assertFailsWith<IllegalStateException> {
                BundleResources.resolveNativeLibrary("whisper")
            }
        }
    }

    @Nested
    inner class PlatformVariations {
        @Test
        fun `works with macos-x64 platform`() {
            BundleResources.init(createConfig(platform = "macos-x64"))
            assertEquals("macos-x64", BundleResources.platform.toString())

            val bundleDir = tempDir.resolve("bundle")
            val versionDir = bundleDir.resolve("versions/100")
            val resourcesDir = versionDir.resolve("resources")

            // Create platform-specific resource
            val platformPath = resourcesDir.resolve("macos-x64/config.json")
            platformPath.parent.createDirectories()
            platformPath.writeText("content")

            val result = BundleResources.resolve("config.json")
            assertEquals(platformPath, result)
        }

        @Test
        fun `works with windows-x64 platform`() {
            BundleResources.init(createConfig(platform = "windows-x64"))
            assertEquals("windows-x64", BundleResources.platform.toString())
        }

        @Test
        fun `works with linux-arm64 platform`() {
            BundleResources.init(createConfig(platform = "linux-arm64"))
            assertEquals("linux-arm64", BundleResources.platform.toString())
        }
    }
}
