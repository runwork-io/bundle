package io.runwork.bundle.common.storage

import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Platform-specific path resolution for bundle storage.
 */
object PlatformPaths {

    /**
     * Get the default application data directory for bundle storage.
     *
     * - macOS: ~/Library/Application Support/{appId}
     * - Windows: %APPDATA%/{appId}
     * - Linux: $XDG_DATA_HOME/{appId} or ~/.local/share/{appId}
     */
    fun getDefaultAppDataDir(appId: String): Path {
        return when (Os.current) {
            Os.MACOS -> {
                val home = System.getProperty("user.home")
                Paths.get(home, "Library", "Application Support", appId)
            }
            Os.WINDOWS -> {
                val appData = System.getenv("APPDATA")
                    ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming").toString()
                Paths.get(appData, appId)
            }
            Os.LINUX -> {
                // Linux/Unix - follow XDG Base Directory Specification
                val xdgData = System.getenv("XDG_DATA_HOME")
                    ?: Paths.get(System.getProperty("user.home"), ".local", "share").toString()
                Paths.get(xdgData, appId)
            }
        }
    }

    /**
     * Get the current platform.
     */
    fun getPlatform(): Platform = Platform.current
}
