package io.runwork.bundle.common

import java.nio.file.Files
import java.nio.file.Path

/**
 * Create a link from dest to source using the appropriate method for the platform.
 * - macOS/Linux: relative symlink (survives directory moves)
 * - Windows: hard link (symlinks require elevated permissions)
 */
fun createLink(dest: Path, source: Path) {
    when (Os.current) {
        Os.WINDOWS -> Files.createLink(dest, source)
        Os.MACOS, Os.LINUX -> {
            val relativeSource = dest.parent.relativize(source)
            Files.createSymbolicLink(dest, relativeSource)
        }
    }
}

/**
 * Check if two paths refer to the same file.
 * Works correctly for both hard links and symlinks.
 */
fun isSameFile(a: Path, b: Path): Boolean {
    return try {
        Files.isSameFile(a, b)
    } catch (e: Exception) {
        false
    }
}
