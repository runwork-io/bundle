package io.runwork.bundle.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformTest {

    @Test
    fun `Platform fromString parses valid platform strings`() {
        val macosArm64 = Platform.fromString("macos-arm64")
        assertEquals(Os.MACOS, macosArm64.os)
        assertEquals(Architecture.ARM64, macosArm64.architecture)

        val macosX86 = Platform.fromString("macos-x86_64")
        assertEquals(Os.MACOS, macosX86.os)
        assertEquals(Architecture.X86_64, macosX86.architecture)

        val windowsX86 = Platform.fromString("windows-x86_64")
        assertEquals(Os.WINDOWS, windowsX86.os)
        assertEquals(Architecture.X86_64, windowsX86.architecture)

        val linuxArm64 = Platform.fromString("linux-arm64")
        assertEquals(Os.LINUX, linuxArm64.os)
        assertEquals(Architecture.ARM64, linuxArm64.architecture)
    }

    @Test
    fun `Platform toString produces correct format`() {
        assertEquals("macos-arm64", Platform(Os.MACOS, Architecture.ARM64).toString())
        assertEquals("windows-x86_64", Platform(Os.WINDOWS, Architecture.X86_64).toString())
        assertEquals("linux-arm64", Platform(Os.LINUX, Architecture.ARM64).toString())
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
    fun `Architecture fromId parses valid architecture identifiers`() {
        assertEquals(Architecture.ARM64, Architecture.fromId("arm64"))
        assertEquals(Architecture.X86_64, Architecture.fromId("x86_64"))
    }

    @Test
    fun `Platform current returns a valid platform`() {
        val platform = Platform.current
        // Just verify it returns something valid
        assert(platform.os in Os.entries)
        assert(platform.architecture in Architecture.entries)
        // And that toString works
        assert(platform.toString().contains("-"))
    }
}
