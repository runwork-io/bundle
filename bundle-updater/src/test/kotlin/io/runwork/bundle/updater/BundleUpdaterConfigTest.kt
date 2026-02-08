package io.runwork.bundle.updater

import io.runwork.bundle.common.BundleLaunchConfig
import io.runwork.bundle.common.Platform
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class BundleUpdaterConfigTest {

    @Test
    fun fromLaunchConfig_mapsAllFields() {
        val launchConfig = BundleLaunchConfig(
            appDataDir = "/data/myapp",
            bundleSubdirectory = "mybundle",
            baseUrl = "https://cdn.example.com/v1",
            publicKey = "dGVzdC1wdWJsaWMta2V5",
            platform = "macos-arm64",
            shellVersion = 5,
            currentBuildNumber = 42,
        )

        val config = BundleUpdaterConfig.fromLaunchConfig(launchConfig)

        assertEquals(Path.of("/data/myapp"), config.appDataDir)
        assertEquals("mybundle", config.bundleSubdirectory)
        assertEquals("https://cdn.example.com/v1", config.baseUrl)
        assertEquals("dGVzdC1wdWJsaWMta2V5", config.publicKey)
        assertEquals(42L, config.currentBuildNumber)
        assertEquals(Platform.fromString("macos-arm64"), config.platform)
        assertEquals(6.hours, config.checkInterval)
    }

    @Test
    fun fromLaunchConfig_respectsCustomCheckInterval() {
        val launchConfig = BundleLaunchConfig(
            appDataDir = "/data/myapp",
            baseUrl = "https://cdn.example.com",
            publicKey = "key",
            platform = "linux-x64",
            shellVersion = 1,
            currentBuildNumber = 1,
        )

        val config = BundleUpdaterConfig.fromLaunchConfig(launchConfig, checkInterval = 30.minutes)

        assertEquals(30.minutes, config.checkInterval)
    }

    @Test
    fun fromLaunchConfig_computesBundleDir() {
        val launchConfig = BundleLaunchConfig(
            appDataDir = "/data/myapp",
            bundleSubdirectory = "sub",
            baseUrl = "https://cdn.example.com",
            publicKey = "key",
            platform = "macos-arm64",
            shellVersion = 1,
            currentBuildNumber = 1,
        )

        val config = BundleUpdaterConfig.fromLaunchConfig(launchConfig)

        assertEquals(Path.of("/data/myapp/sub"), config.bundleDir)
    }

    @Test
    fun fromLaunchConfig_emptySubdirectory() {
        val launchConfig = BundleLaunchConfig(
            appDataDir = "/data/myapp",
            bundleSubdirectory = "",
            baseUrl = "https://cdn.example.com",
            publicKey = "key",
            platform = "macos-arm64",
            shellVersion = 1,
            currentBuildNumber = 1,
        )

        val config = BundleUpdaterConfig.fromLaunchConfig(launchConfig)

        assertEquals(Path.of("/data/myapp"), config.bundleDir)
    }
}
