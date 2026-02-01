package io.runwork.bundle.storage

/**
 * Platform-specific path resolution for bundle storage.
 */
object PlatformPaths {

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
}
