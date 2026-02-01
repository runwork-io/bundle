package samples

/**
 * Detect the current platform for bundle downloads.
 *
 * Returns a platform identifier in the format {os}-{arch}, e.g.:
 * - macos-arm64
 * - macos-x86_64
 * - windows-x86_64
 * - linux-arm64
 * - linux-x86_64
 */
fun detectPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch")
    return when {
        os.contains("mac") && arch == "aarch64" -> "macos-arm64"
        os.contains("mac") -> "macos-x86_64"
        os.contains("windows") -> "windows-x86_64"
        os.contains("linux") && arch == "aarch64" -> "linux-arm64"
        else -> "linux-x86_64"
    }
}
