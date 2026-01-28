package io.runwork.bundle.storage

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Platform-specific path resolution for bundle storage.
 */
object PlatformPaths {

    /**
     * Get the application data directory for bundle storage.
     *
     * - macOS: ~/Library/Application Support/{appId}
     * - Windows: %APPDATA%/{appId}
     * - Linux: $XDG_DATA_HOME/{appId} or ~/.local/share/{appId}
     */
    fun getAppDataDir(appId: String): Path {
        val osName = System.getProperty("os.name").lowercase()

        return when {
            osName.contains("mac") || osName.contains("darwin") -> {
                val home = System.getProperty("user.home")
                Paths.get(home, "Library", "Application Support", appId)
            }
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")
                    ?: Paths.get(System.getProperty("user.home"), "AppData", "Roaming").toString()
                Paths.get(appData, appId)
            }
            else -> {
                // Linux/Unix - follow XDG Base Directory Specification
                val xdgData = System.getenv("XDG_DATA_HOME")
                    ?: Paths.get(System.getProperty("user.home"), ".local", "share").toString()
                Paths.get(xdgData, appId)
            }
        }
    }

    /**
     * Get the platform identifier string.
     *
     * Returns one of: macos-arm64, macos-x86_64, windows-x86_64, linux-x86_64
     */
    fun getPlatform(): String {
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch")

        val os = when {
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("win") -> "windows"
            else -> "linux"
        }

        val archName = when {
            arch == "aarch64" || arch == "arm64" -> "arm64"
            arch.contains("64") || arch == "amd64" -> "x86_64"
            else -> arch
        }

        return "$os-$archName"
    }

    /**
     * Check if the current platform is Windows.
     */
    fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase().contains("win")
    }

    /**
     * Check if the current platform is macOS.
     */
    fun isMacOS(): Boolean {
        val osName = System.getProperty("os.name").lowercase()
        return osName.contains("mac") || osName.contains("darwin")
    }

    /**
     * Check if the current platform is Linux.
     */
    fun isLinux(): Boolean {
        val osName = System.getProperty("os.name").lowercase()
        return !osName.contains("mac") && !osName.contains("darwin") && !osName.contains("win")
    }
}
