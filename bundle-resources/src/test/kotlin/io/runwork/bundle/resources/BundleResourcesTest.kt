package io.runwork.bundle.resources

import io.runwork.bundle.common.BundleLaunchConfig
import io.runwork.bundle.common.Platform
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
        val config = createConfig()

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
            val config1 = createConfig(buildNumber = 100L)
            BundleResources.init(config1)
            assertTrue(BundleResources.isInitialized)

            BundleResources.reset()
            assertFalse(BundleResources.isInitialized)

            val config2 = createConfig(buildNumber = 200L)
            BundleResources.init(config2)
            assertTrue(BundleResources.isInitialized)
            assertTrue(BundleResources.versionDir.toString().contains("200"))
        }
    }

    @Nested
    inner class Resolution {
        private val currentPlatform = Platform.current

        @BeforeEach
        fun initResources() {
            BundleResources.init(createConfig())
        }

        @Test
        fun `resolve returns null when resource not found`() {
            val result = BundleResources.resolve("nonexistent/file.txt")
            assertNull(result)
        }

        @Test
        fun `resolve finds platform-specific resource`() {
            val platformPath = createResource(currentPlatform.toString(), "config.json")

            val result = BundleResources.resolve("config.json")
            assertNotNull(result)
            assertEquals(platformPath, result)
        }

        @Test
        fun `resolve finds os-only resource`() {
            val osPath = createResource(currentPlatform.os.id, "config.json")

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
            val platformPath = createResource(currentPlatform.toString(), "config.json", content = "platform")
            createResource(currentPlatform.os.id, "config.json", content = "os")
            createResource("common", "config.json", content = "common")

            val result = BundleResources.resolve("config.json")
            assertNotNull(result)
            assertEquals(platformPath, result)
        }

        @Test
        fun `resolve prefers os-only over common`() {
            val osPath = createResource(currentPlatform.os.id, "config.json", content = "os")
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
            assertTrue(exception.searchedLocations[0].toString().contains(currentPlatform.toString()))
            assertTrue(exception.searchedLocations[1].toString().contains(currentPlatform.os.id))
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
        private val currentPlatform = Platform.current

        private fun nativeLibraryFilename(name: String): String {
            return when (currentPlatform.os) {
                io.runwork.bundle.common.Os.MACOS -> "lib$name.dylib"
                io.runwork.bundle.common.Os.WINDOWS -> "$name.dll"
                io.runwork.bundle.common.Os.LINUX -> "lib$name.so"
            }
        }

        @Test
        fun `resolveNativeLibrary finds library with platform-specific naming`() {
            BundleResources.init(createConfig())
            val libFilename = nativeLibraryFilename("whisper")
            val libPath = createResource(currentPlatform.toString(), libFilename)

            val result = BundleResources.resolveNativeLibrary("whisper")
            assertNotNull(result)
            assertEquals(libPath, result)
        }

        @Test
        fun `resolveNativeLibrary returns null when not found`() {
            BundleResources.init(createConfig())

            val result = BundleResources.resolveNativeLibrary("nonexistent")
            assertNull(result)
        }

        @Test
        fun `resolveNativeLibrary uses platform priority`() {
            BundleResources.init(createConfig())

            val libFilename = nativeLibraryFilename("whisper")

            // Create lib in common
            val bundleDir = tempDir.resolve("bundle")
            val versionDir = bundleDir.resolve("versions/100")
            val resourcesDir = versionDir.resolve("resources")
            val commonPath = resourcesDir.resolve("common/$libFilename")
            commonPath.parent.createDirectories()
            commonPath.writeText("common lib")

            // Create lib in platform-specific
            val platformPath = resourcesDir.resolve("${currentPlatform}/$libFilename")
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

}
