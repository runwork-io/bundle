package io.runwork.bundle.common

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the system properties and environment that [restartProcess] depends on.
 * Since [restartProcess] calls exitProcess, we verify its prerequisites instead.
 */
class ProcessRestartTest {

    @Test
    fun `java home property is set and contains java binary`() {
        val javaHome = System.getProperty("java.home")
        assertNotNull(javaHome, "java.home should be set")

        val javaBin = when (Os.current) {
            Os.WINDOWS -> Path.of(javaHome, "bin", "java.exe")
            Os.MACOS, Os.LINUX -> Path.of(javaHome, "bin", "java")
        }
        assertTrue(javaBin.toFile().exists(), "Java binary should exist at: $javaBin")
    }

    @Test
    fun `java class path property is set`() {
        val classpath = System.getProperty("java.class.path")
        assertNotNull(classpath, "java.class.path should be set")
        assertTrue(classpath.isNotEmpty(), "java.class.path should not be empty")
    }

    @Test
    fun `sun java command property is set with non-empty main class`() {
        val sunJavaCommand = System.getProperty("sun.java.command")
        assertNotNull(sunJavaCommand, "sun.java.command should be set")
        val mainClass = sunJavaCommand.split(" ").first()
        assertTrue(mainClass.isNotEmpty(), "Main class should not be empty")
    }

    @Test
    fun `java binary from java home can run java version`() {
        val javaHome = System.getProperty("java.home")!!
        val javaBin = when (Os.current) {
            Os.WINDOWS -> Path.of(javaHome, "bin", "java.exe").toString()
            Os.MACOS, Os.LINUX -> Path.of(javaHome, "bin", "java").toString()
        }
        val process = ProcessBuilder(javaBin, "-version")
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "java -version should exit with code 0")
    }
}
