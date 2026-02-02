package io.runwork.bundle.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformTest {

    @Test
    fun `Platform fromString parses valid platform strings`() {
        val macosArm64 = Platform.fromString("macos-arm64")
        assertEquals(Os.MACOS, macosArm64.os)
        assertEquals(Arch.ARM64, macosArm64.arch)

        val macosX64 = Platform.fromString("macos-x64")
        assertEquals(Os.MACOS, macosX64.os)
        assertEquals(Arch.X64, macosX64.arch)

        val windowsX64 = Platform.fromString("windows-x64")
        assertEquals(Os.WINDOWS, windowsX64.os)
        assertEquals(Arch.X64, windowsX64.arch)

        val linuxArm64 = Platform.fromString("linux-arm64")
        assertEquals(Os.LINUX, linuxArm64.os)
        assertEquals(Arch.ARM64, linuxArm64.arch)
    }

    @Test
    fun `Platform fromString accepts legacy x86_64 architecture`() {
        // Should accept x86_64 and map it to X64
        val macosX64 = Platform.fromString("macos-x86_64")
        assertEquals(Os.MACOS, macosX64.os)
        assertEquals(Arch.X64, macosX64.arch)
    }

    @Test
    fun `Platform toString produces correct format`() {
        assertEquals("macos-arm64", Platform(Os.MACOS, Arch.ARM64).toString())
        assertEquals("windows-x64", Platform(Os.WINDOWS, Arch.X64).toString())
        assertEquals("linux-arm64", Platform(Os.LINUX, Arch.ARM64).toString())
    }

    @Test
    fun `Platform fromString throws on invalid format`() {
        assertFailsWith<IllegalArgumentException> {
            Platform.fromString("invalid")
        }
        assertFailsWith<IllegalArgumentException> {
            Platform.fromString("macos-arm64-extra")
        }
        assertFailsWith<IllegalArgumentException> {
            Platform.fromString("")
        }
    }

    @Test
    fun `Platform fromString throws on unknown OS`() {
        assertFailsWith<IllegalArgumentException> {
            Platform.fromString("freebsd-arm64")
        }
    }

    @Test
    fun `Platform fromString throws on unknown architecture`() {
        assertFailsWith<IllegalArgumentException> {
            Platform.fromString("macos-mips")
        }
    }

    @Test
    fun `Os fromId parses valid OS identifiers`() {
        assertEquals(Os.MACOS, Os.fromId("macos"))
        assertEquals(Os.WINDOWS, Os.fromId("windows"))
        assertEquals(Os.LINUX, Os.fromId("linux"))
    }

    @Test
    fun `Arch fromId parses valid architecture identifiers`() {
        assertEquals(Arch.ARM64, Arch.fromId("arm64"))
        assertEquals(Arch.X64, Arch.fromId("x64"))
        // Also accepts legacy x86_64
        assertEquals(Arch.X64, Arch.fromId("x86_64"))
    }

    @Test
    fun `Platform current returns a valid platform`() {
        val platform = Platform.current
        // Just verify it returns something valid
        assert(platform.os in Os.entries)
        assert(platform.arch in Arch.entries)
        // And that toString works
        assert(platform.toString().contains("-"))
    }
}
