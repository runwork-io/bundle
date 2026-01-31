package io.runwork.bundle.loader

import io.runwork.bundle.BundleManager
import io.runwork.bundle.TestFixtures
import io.runwork.bundle.UpdateCheckResult
import io.runwork.bundle.testing.TestBundle
import io.runwork.bundle.testing.TestBundleServer
import io.runwork.bundle.testing.assertSuccess
import io.runwork.bundle.testing.assertUpdateAvailable
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for BundleLoader - verifying that downloaded bundles can actually be loaded and executed.
 */
class BundleLoaderTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("bundle-loader-test")
    }

    @AfterTest
    fun tearDown() {
        TestFixtures.deleteRecursively(tempDir)
    }

    /**
     * Helper to run test with proper BundleManager cleanup.
     */
    private inline fun <T> BundleManager.use(block: (BundleManager) -> T): T {
        return try {
            block(this)
        } finally {
            close()
        }
    }

    @Test
    fun loadBundle_executesMainMethod() = runTest {
        // Create a JAR with a main class that simply returns
        val mainClassName = "io.runwork.test.TestMain"
        val jarBytes = createJarWithMainClass(mainClassName)

        val bundle = TestBundle.create {
            mainClass = mainClassName
            jar("app.jar", jarBytes)
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )

        BundleManager(config).use { bundleManager ->
            // Download the bundle
            val checkResult = bundleManager.checkForUpdate()
            checkResult.assertUpdateAvailable()

            val downloadResult = bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}
            downloadResult.assertSuccess()

            // Load the bundle
            val loadedBundle = bundleManager.loadBundle()
            assertNotNull(loadedBundle)

            // Wait for main to complete by joining the thread directly
            // (The onExit callback has a race condition if main finishes very quickly)
            loadedBundle.mainThread.join(5000)
            assertTrue(!loadedBundle.mainThread.isAlive, "Main thread should complete within 5 seconds")

            // Verify the main class was loaded from the bundle's classloader
            assertEquals(loadedBundle.classLoader, loadedBundle.classLoader.loadClass(mainClassName).classLoader)
        }
    }

    @Test
    fun loadBundle_failsWithMissingMainClass() = runTest {
        // Create a JAR without the main class
        val jarBytes = createEmptyJar()

        val bundle = TestBundle.create {
            mainClass = "io.runwork.NonExistent"
            jar("app.jar", jarBytes)
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )

        BundleManager(config).use { bundleManager ->
            // Download
            val checkResult = bundleManager.checkForUpdate()
            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            // Load should fail
            try {
                bundleManager.loadBundle()
                fail("Should throw BundleLoadException for missing main class")
            } catch (e: BundleLoadException) {
                assertTrue(e.message!!.contains("Main class not found"))
            }
        }
    }

    @Test
    fun loadBundle_failsWithNonStaticMain() = runTest {
        // Create a JAR with a non-static main method
        val mainClassName = "io.runwork.test.NonStaticMain"
        val jarBytes = createJarWithNonStaticMainClass(mainClassName)

        val bundle = TestBundle.create {
            mainClass = mainClassName
            jar("app.jar", jarBytes)
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )

        BundleManager(config).use { bundleManager ->
            // Download
            val checkResult = bundleManager.checkForUpdate()
            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            // Load should fail
            try {
                bundleManager.loadBundle()
                fail("Should throw BundleLoadException for non-static main")
            } catch (e: BundleLoadException) {
                assertTrue(e.message!!.contains("must be static"))
            }
        }
    }

    @Test
    fun loadBundle_reportsExceptionFromMain() = runTest {
        // Create a JAR with a main that throws an exception
        val mainClassName = "io.runwork.test.ThrowingMain"
        val jarBytes = createJarWithThrowingMainClass(mainClassName)

        val bundle = TestBundle.create {
            mainClass = mainClassName
            jar("app.jar", jarBytes)
            buildNumber = 1
        }

        val server = TestBundleServer.create(tempDir.resolve("server"))
        server.publish(bundle, includeZip = true)

        val config = server.bundleConfig(
            publicKey = bundle.publicKeyBase64!!,
            appDataDir = tempDir.resolve("app")
        )

        BundleManager(config).use { bundleManager ->
            // Download
            val checkResult = bundleManager.checkForUpdate()
            bundleManager.downloadUpdate(
                (checkResult as UpdateCheckResult.UpdateAvailable).info
            ) {}.assertSuccess()

            // Load
            val loadedBundle = bundleManager.loadBundle()

            // Wait for main to complete and capture the exception
            val exitLatch = CountDownLatch(1)
            val exitException = AtomicReference<Throwable?>(null)
            loadedBundle.onExit { throwable ->
                exitException.set(throwable)
                exitLatch.countDown()
            }

            assertTrue(exitLatch.await(5, TimeUnit.SECONDS))

            // Verify an exception was reported
            assertNotNull(exitException.get(), "Main should have thrown an exception")
            assertTrue(exitException.get()!!.message!!.contains("Test exception"))
        }
    }

    // ========== Bytecode Generation Helpers ==========

    /**
     * Creates a JAR containing a class with a static main method that simply returns.
     */
    private fun createJarWithMainClass(className: String): ByteArray {
        val classPath = className.replace('.', '/') + ".class"
        val classBytes = createMainClassBytecode(className.replace('.', '/'))

        return createJar(mapOf(classPath to classBytes))
    }

    /**
     * Creates an empty JAR file.
     */
    private fun createEmptyJar(): ByteArray {
        return createJar(emptyMap())
    }

    /**
     * Creates a JAR containing a class with a non-static main method.
     */
    private fun createJarWithNonStaticMainClass(className: String): ByteArray {
        val classPath = className.replace('.', '/') + ".class"
        val classBytes = createNonStaticMainClassBytecode(className.replace('.', '/'))

        return createJar(mapOf(classPath to classBytes))
    }

    /**
     * Creates a JAR containing a class with a static main method that throws an exception.
     */
    private fun createJarWithThrowingMainClass(className: String): ByteArray {
        val classPath = className.replace('.', '/') + ".class"
        val classBytes = createThrowingMainClassBytecode(className.replace('.', '/'))

        return createJar(mapOf(classPath to classBytes))
    }

    private fun createJar(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        JarOutputStream(baos).use { jos ->
            for ((name, content) in entries) {
                jos.putNextEntry(JarEntry(name))
                jos.write(content)
                jos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /**
     * Creates bytecode for a class with a static main(String[] args) method that returns immediately.
     *
     * Equivalent to:
     * ```java
     * public class ClassName {
     *     public static void main(String[] args) {
     *         // do nothing
     *     }
     * }
     * ```
     */
    private fun createMainClassBytecode(className: String): ByteArray {
        return ByteArrayOutputStream().apply {
            // Magic number
            write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

            // Version (Java 8: major=52, minor=0)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

            // Constant pool count = 11 (indices 1-10)
            write(byteArrayOf(0x00, 0x0B))

            // #1: CONSTANT_Class -> #2 (this class)
            write(byteArrayOf(0x07, 0x00, 0x02))

            // #2: CONSTANT_Utf8 -> class name
            writeUtf8(className)

            // #3: CONSTANT_Class -> #4 (java/lang/Object)
            write(byteArrayOf(0x07, 0x00, 0x04))

            // #4: CONSTANT_Utf8 -> java/lang/Object
            writeUtf8("java/lang/Object")

            // #5: CONSTANT_Utf8 -> main
            writeUtf8("main")

            // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
            writeUtf8("([Ljava/lang/String;)V")

            // #7: CONSTANT_Utf8 -> Code
            writeUtf8("Code")

            // #8: CONSTANT_Utf8 -> <init>
            writeUtf8("<init>")

            // #9: CONSTANT_Utf8 -> ()V
            writeUtf8("()V")

            // #10: CONSTANT_NameAndType -> #8:#9 (<init>:()V)
            write(byteArrayOf(0x0C, 0x00, 0x08, 0x00, 0x09))

            // Access flags: public
            write(byteArrayOf(0x00, 0x21))

            // This class: #1
            write(byteArrayOf(0x00, 0x01))

            // Super class: #3
            write(byteArrayOf(0x00, 0x03))

            // Interfaces count: 0
            write(byteArrayOf(0x00, 0x00))

            // Fields count: 0
            write(byteArrayOf(0x00, 0x00))

            // Methods count: 1
            write(byteArrayOf(0x00, 0x01))

            // Method: public static void main(String[] args)
            write(byteArrayOf(0x00, 0x09)) // access_flags: public static
            write(byteArrayOf(0x00, 0x05)) // name_index: #5 (main)
            write(byteArrayOf(0x00, 0x06)) // descriptor_index: #6
            write(byteArrayOf(0x00, 0x01)) // attributes_count: 1

            // Code attribute
            write(byteArrayOf(0x00, 0x07)) // attribute_name_index: #7 (Code)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x0D)) // attribute_length: 13
            write(byteArrayOf(0x00, 0x00)) // max_stack: 0
            write(byteArrayOf(0x00, 0x01)) // max_locals: 1 (args)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)) // code_length: 1
            write(0xB1) // return
            write(byteArrayOf(0x00, 0x00)) // exception_table_length: 0
            write(byteArrayOf(0x00, 0x00)) // code_attributes_count: 0

            // Class attributes count: 0
            write(byteArrayOf(0x00, 0x00))
        }.toByteArray()
    }

    /**
     * Creates bytecode for a class with a non-static main method.
     */
    private fun createNonStaticMainClassBytecode(className: String): ByteArray {
        return ByteArrayOutputStream().apply {
            // Magic number
            write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

            // Version (Java 8)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

            // Constant pool count = 11
            write(byteArrayOf(0x00, 0x0B))

            // #1: Class this
            write(byteArrayOf(0x07, 0x00, 0x02))
            // #2: Utf8 class name
            writeUtf8(className)
            // #3: Class Object
            write(byteArrayOf(0x07, 0x00, 0x04))
            // #4: Utf8 java/lang/Object
            writeUtf8("java/lang/Object")
            // #5: Utf8 main
            writeUtf8("main")
            // #6: Utf8 ([Ljava/lang/String;)V
            writeUtf8("([Ljava/lang/String;)V")
            // #7: Utf8 Code
            writeUtf8("Code")
            // #8: Utf8 <init>
            writeUtf8("<init>")
            // #9: Utf8 ()V
            writeUtf8("()V")
            // #10: NameAndType #8:#9
            write(byteArrayOf(0x0C, 0x00, 0x08, 0x00, 0x09))

            // Access flags: public
            write(byteArrayOf(0x00, 0x21))
            // This class: #1
            write(byteArrayOf(0x00, 0x01))
            // Super class: #3
            write(byteArrayOf(0x00, 0x03))
            // Interfaces count: 0
            write(byteArrayOf(0x00, 0x00))
            // Fields count: 0
            write(byteArrayOf(0x00, 0x00))
            // Methods count: 1
            write(byteArrayOf(0x00, 0x01))

            // Method: public void main(String[] args) - NOT static!
            write(byteArrayOf(0x00, 0x01)) // access_flags: public (NOT static)
            write(byteArrayOf(0x00, 0x05)) // name_index: #5 (main)
            write(byteArrayOf(0x00, 0x06)) // descriptor_index: #6
            write(byteArrayOf(0x00, 0x01)) // attributes_count: 1

            // Code attribute
            write(byteArrayOf(0x00, 0x07)) // attribute_name_index: #7 (Code)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x0D)) // attribute_length: 13
            write(byteArrayOf(0x00, 0x00)) // max_stack: 0
            write(byteArrayOf(0x00, 0x02)) // max_locals: 2 (this + args)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)) // code_length: 1
            write(0xB1) // return
            write(byteArrayOf(0x00, 0x00)) // exception_table_length: 0
            write(byteArrayOf(0x00, 0x00)) // code_attributes_count: 0

            // Class attributes count: 0
            write(byteArrayOf(0x00, 0x00))
        }.toByteArray()
    }

    /**
     * Creates bytecode for a class that throws an exception from main.
     *
     * Equivalent to:
     * ```java
     * public class ClassName {
     *     public static void main(String[] args) {
     *         throw new RuntimeException("Test exception");
     *     }
     * }
     * ```
     */
    private fun createThrowingMainClassBytecode(className: String): ByteArray {
        return ByteArrayOutputStream().apply {
            // Magic number
            write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

            // Version (Java 8)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

            // Constant pool count = 16 (indices 1-15)
            write(byteArrayOf(0x00, 0x10))

            // #1: Class this
            write(byteArrayOf(0x07, 0x00, 0x02))
            // #2: Utf8 class name
            writeUtf8(className)
            // #3: Class Object
            write(byteArrayOf(0x07, 0x00, 0x04))
            // #4: Utf8 java/lang/Object
            writeUtf8("java/lang/Object")
            // #5: Utf8 main
            writeUtf8("main")
            // #6: Utf8 ([Ljava/lang/String;)V
            writeUtf8("([Ljava/lang/String;)V")
            // #7: Utf8 Code
            writeUtf8("Code")
            // #8: Class RuntimeException
            write(byteArrayOf(0x07, 0x00, 0x09))
            // #9: Utf8 java/lang/RuntimeException
            writeUtf8("java/lang/RuntimeException")
            // #10: Utf8 <init>
            writeUtf8("<init>")
            // #11: Utf8 (Ljava/lang/String;)V
            writeUtf8("(Ljava/lang/String;)V")
            // #12: NameAndType #10:#11
            write(byteArrayOf(0x0C, 0x00, 0x0A, 0x00, 0x0B))
            // #13: Methodref #8.#12 (RuntimeException.<init>(String))
            write(byteArrayOf(0x0A, 0x00, 0x08, 0x00, 0x0C))
            // #14: Utf8 "Test exception"
            writeUtf8("Test exception")
            // #15: String #14 - THIS IS THE KEY FIX: ldc needs a String constant, not Utf8
            write(byteArrayOf(0x08, 0x00, 0x0E))

            // Access flags: public
            write(byteArrayOf(0x00, 0x21))
            // This class: #1
            write(byteArrayOf(0x00, 0x01))
            // Super class: #3
            write(byteArrayOf(0x00, 0x03))
            // Interfaces count: 0
            write(byteArrayOf(0x00, 0x00))
            // Fields count: 0
            write(byteArrayOf(0x00, 0x00))
            // Methods count: 1
            write(byteArrayOf(0x00, 0x01))

            // Method: public static void main(String[] args)
            write(byteArrayOf(0x00, 0x09)) // access_flags: public static
            write(byteArrayOf(0x00, 0x05)) // name_index: #5 (main)
            write(byteArrayOf(0x00, 0x06)) // descriptor_index: #6
            write(byteArrayOf(0x00, 0x01)) // attributes_count: 1

            // Code attribute
            // Bytecode: new #8; dup; ldc #15; invokespecial #13; athrow
            // = BB 00 08 59 12 0F B7 00 0D BF = 10 bytes
            write(byteArrayOf(0x00, 0x07)) // attribute_name_index: #7 (Code)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x16)) // attribute_length: 22 (12 header + 10 code)
            write(byteArrayOf(0x00, 0x03)) // max_stack: 3
            write(byteArrayOf(0x00, 0x01)) // max_locals: 1
            write(byteArrayOf(0x00, 0x00, 0x00, 0x0A)) // code_length: 10
            // new RuntimeException (#8)
            write(byteArrayOf(0xBB.toByte(), 0x00, 0x08))
            // dup
            write(0x59)
            // ldc "Test exception" (#15 - String constant)
            write(byteArrayOf(0x12, 0x0F))
            // invokespecial RuntimeException.<init>(String) (#13)
            write(byteArrayOf(0xB7.toByte(), 0x00, 0x0D))
            // athrow
            write(0xBF)
            write(byteArrayOf(0x00, 0x00)) // exception_table_length: 0
            write(byteArrayOf(0x00, 0x00)) // code_attributes_count: 0

            // Class attributes count: 0
            write(byteArrayOf(0x00, 0x00))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(0x01) // CONSTANT_Utf8
        write((bytes.size shr 8) and 0xFF)
        write(bytes.size and 0xFF)
        write(bytes)
    }
}
