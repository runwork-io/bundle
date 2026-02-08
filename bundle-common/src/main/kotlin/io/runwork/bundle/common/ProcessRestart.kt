package io.runwork.bundle.common

import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Describes the command needed to launch the current JVM process.
 */
private data class ProcessCommand(
    val javaExecutable: String,
    val jvmArgs: List<String>,
    val classpath: String,
    val mainClass: String,
    val programArgs: List<String>,
) {
    /**
     * Builds the full command list: [java, ...jvmArgs, -cp, classpath, mainClass, ...programArgs]
     */
    fun toCommandList(): List<String> = buildList {
        add(javaExecutable)
        addAll(jvmArgs)
        add("-cp")
        add(classpath)
        add(mainClass)
        addAll(programArgs)
    }
}

/**
 * Auto-detect the command that was used to start the current JVM process.
 *
 * Uses system properties and RuntimeMXBean to reconstruct the full command:
 * - Java binary from `java.home`
 * - JVM args from `RuntimeMXBean.inputArguments`
 * - Classpath from `java.class.path`
 * - Main class and program args from `sun.java.command`
 *
 * @throws IllegalStateException if any required property is missing
 */
private fun detectProcessCommand(): ProcessCommand {
    val javaHome = System.getProperty("java.home")
        ?: error("System property 'java.home' is not set")

    val javaExecutable = when (Os.current) {
        Os.WINDOWS -> Path.of(javaHome, "bin", "java.exe").toString()
        Os.MACOS, Os.LINUX -> Path.of(javaHome, "bin", "java").toString()
    }

    val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments

    val classpath = System.getProperty("java.class.path")
        ?: error("System property 'java.class.path' is not set")

    val sunJavaCommand = System.getProperty("sun.java.command")
        ?: error("System property 'sun.java.command' is not set")

    val commandParts = sunJavaCommand.split(" ")
    val mainClass = commandParts.first()
    val programArgs = commandParts.drop(1)

    return ProcessCommand(
        javaExecutable = javaExecutable,
        jvmArgs = jvmArgs,
        classpath = classpath,
        mainClass = mainClass,
        programArgs = programArgs,
    )
}

/**
 * Restart the current JVM process by spawning a new identical process and exiting the current one.
 *
 * This uses a spawn-then-exit strategy: a new process is started with the same JVM arguments,
 * classpath, main class, and program arguments, then the current process exits. There will be
 * a brief overlap where both processes are running.
 *
 * The bundle runs on a thread within the shell's JVM (not a separate process), so
 * `sun.java.command` contains the shell's main class — exactly what we need to restart the shell.
 *
 * @param exitCode the exit code for the current process (default 0)
 */
fun restartProcess(exitCode: Int = 0): Nothing {
    val command = detectProcessCommand()
    val workingDir = Path.of(System.getProperty("user.dir")).toFile()
    ProcessBuilder(command.toCommandList())
        .inheritIO()
        .directory(workingDir)
        .start()
    exitProcess(exitCode)
}
