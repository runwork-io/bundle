package io.runwork.bundle.common

import kotlinx.serialization.Serializable

/**
 * Supported operating systems.
 */
@Serializable
enum class Os(val id: String) {
    MACOS("macos"),
    WINDOWS("windows"),
    LINUX("linux");

    companion object {
        /**
         * Parse an OS from its string identifier.
         *
         * @throws IllegalArgumentException if the OS is not recognized
         */
        fun fromId(id: String): Os {
            return entries.find { it.id == id }
                ?: throw IllegalArgumentException("Unknown OS: $id")
        }

        /**
         * The current OS, detected from system properties.
         */
        val current: Os by lazy {
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.contains("mac") || osName.contains("darwin") -> MACOS
                osName.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/**
 * Supported CPU architectures.
 *
 * Uses Compose Desktop naming convention: arm64, x64
 */
@Serializable
enum class Arch(val id: String) {
    ARM64("arm64"),
    X64("x64");

    companion object {
        /**
         * Parse an architecture from its string identifier.
         * Accepts both "x64" (preferred) and "x86_64" (legacy) for compatibility.
         *
         * @throws IllegalArgumentException if the architecture is not recognized
         */
        fun fromId(id: String): Arch {
            return when (id) {
                "x64", "x86_64" -> X64
                "arm64" -> ARM64
                else -> throw IllegalArgumentException("Unknown architecture: $id")
            }
        }

        /**
         * The current architecture, detected from system properties.
         */
        val current: Arch by lazy {
            val arch = System.getProperty("os.arch")
            when {
                arch == "aarch64" || arch == "arm64" -> ARM64
                arch.contains("64") || arch == "amd64" -> X64
                else -> throw IllegalArgumentException("Unsupported architecture: $arch")
            }
        }
    }
}

/**
 * Represents a platform (OS + architecture combination).
 *
 * Provides type-safe platform handling while maintaining compatibility with
 * the string-based format used in manifests (e.g., "macos-arm64").
 */
data class Platform(
    val os: Os,
    val arch: Arch,
) {
    /**
     * Returns the platform identifier string (e.g., "macos-arm64").
     */
    override fun toString(): String = "${os.id}-${arch.id}"

    companion object {
        /**
         * Parse a platform from its string identifier (e.g., "macos-arm64").
         * Accepts both "x64" and "x86_64" for the architecture portion.
         *
         * @throws IllegalArgumentException if the platform string is malformed or uses unknown values
         */
        fun fromString(platform: String): Platform {
            val parts = platform.split("-")
            require(parts.size == 2) { "Invalid platform format: $platform (expected 'os-arch')" }
            return Platform(
                os = Os.fromId(parts[0]),
                arch = Arch.fromId(parts[1]),
            )
        }

        /**
         * The current platform, detected from system properties.
         */
        val current: Platform by lazy {
            Platform(
                os = Os.current,
                arch = Arch.current,
            )
        }
    }
}
