package io.runwork.bundle.bootstrap

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.Os
import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import io.runwork.bundle.common.verification.HashVerifier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellMessageBridgeTest {

    private lateinit var tempDir: Path
    private lateinit var appDataDir: Path
    private lateinit var keyPair: TestKeyPair

    private val json = BundleJson.decodingJson
    private val platformStr = "macos-arm64"

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("shell-msg-bridge-test")
        appDataDir = tempDir.resolve("app-data")
        Files.createDirectories(appDataDir)
        keyPair = generateTestKeyPair()
        // Clear any system property used for test communication
        System.clearProperty("shell-message-bridge-test")
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("shell-message-bridge-test")
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun launch_messagesDeliveredViaMessageHandlerClass() = runTest {
        val jarPath = createJarWithStaticOnShellMessage("com/test/MessageHandler")
        val jarContent = Files.readAllBytes(jarPath)
        val bundleFile = createBundleFile("app.jar", jarContent)
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            mainClass = "com.test.MessageHandler",
            shellMessageHandlerClass = "com.test.MessageHandler",
        )

        setupBundle(manifest, mapOf(bundleFile.hash to jarContent))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(result)

        val loadedBundle = bootstrap.launch(result)
        try {
            assertNotNull(loadedBundle.sendMessage)

            loadedBundle.sendMessage.invoke("""{"type":"UpdateReady","newBuildNumber":2,"currentBuildNumber":1}""")

            // The onShellMessage handler sets a system property
            val received = System.getProperty("shell-message-bridge-test")
            assertNotNull(received, "Expected message to be delivered via onShellMessage")
            assertTrue(received.contains("UpdateReady"))
        } finally {
            loadedBundle.mainThread.join(5000)
            loadedBundle.close()
        }
    }

    @Test
    fun launch_sendMessageNullWhenMessageHandlerClassNotSet() = runTest {
        val jarPath = createJarWithStaticMain("com/test/SimpleMain")
        val jarContent = Files.readAllBytes(jarPath)
        val bundleFile = createBundleFile("app.jar", jarContent)
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            mainClass = "com.test.SimpleMain",
            shellMessageHandlerClass = null,
        )

        setupBundle(manifest, mapOf(bundleFile.hash to jarContent))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(result)

        val loadedBundle = bootstrap.launch(result)
        try {
            assertNull(loadedBundle.sendMessage)
        } finally {
            loadedBundle.mainThread.join(5000)
            loadedBundle.close()
        }
    }

    @Test
    fun launch_sendMessageNullWhenClassNotFound() = runTest {
        val jarPath = createJarWithStaticMain("com/test/SimpleMain")
        val jarContent = Files.readAllBytes(jarPath)
        val bundleFile = createBundleFile("app.jar", jarContent)
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            mainClass = "com.test.SimpleMain",
            shellMessageHandlerClass = "com.test.NonExistent",
        )

        setupBundle(manifest, mapOf(bundleFile.hash to jarContent))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(result)

        val loadedBundle = bootstrap.launch(result)
        try {
            assertNull(loadedBundle.sendMessage)
        } finally {
            loadedBundle.mainThread.join(5000)
            loadedBundle.close()
        }
    }

    @Test
    fun launch_sendMessageNullWhenMethodNotFound() = runTest {
        // Class exists but has no onShellMessage method
        val jarPath = createJarWithStaticMain("com/test/NoMessageHandler")
        val jarContent = Files.readAllBytes(jarPath)
        val bundleFile = createBundleFile("app.jar", jarContent)
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            mainClass = "com.test.NoMessageHandler",
            shellMessageHandlerClass = "com.test.NoMessageHandler",
        )

        setupBundle(manifest, mapOf(bundleFile.hash to jarContent))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(result)

        val loadedBundle = bootstrap.launch(result)
        try {
            assertNull(loadedBundle.sendMessage)
        } finally {
            loadedBundle.mainThread.join(5000)
            loadedBundle.close()
        }
    }

    @Test
    fun launch_sendMessageNullWhenMethodNotStatic() = runTest {
        val jarPath = createJarWithNonStaticOnShellMessage("com/test/InstanceHandler")
        val jarContent = Files.readAllBytes(jarPath)
        val bundleFile = createBundleFile("app.jar", jarContent)
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            mainClass = "com.test.InstanceHandler",
            shellMessageHandlerClass = "com.test.InstanceHandler",
        )

        setupBundle(manifest, mapOf(bundleFile.hash to jarContent))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(result)

        val loadedBundle = bootstrap.launch(result)
        try {
            assertNull(loadedBundle.sendMessage)
        } finally {
            loadedBundle.mainThread.join(5000)
            loadedBundle.close()
        }
    }

    @Test
    fun launch_exceptionInHandlerIsSwallowed() = runTest {
        val jarPath = createJarWithThrowingOnShellMessage("com/test/ThrowingHandler")
        val jarContent = Files.readAllBytes(jarPath)
        val bundleFile = createBundleFile("app.jar", jarContent)
        val manifest = createSignedManifest(
            files = listOf(bundleFile),
            mainClass = "com.test.ThrowingHandler",
            shellMessageHandlerClass = "com.test.ThrowingHandler",
        )

        setupBundle(manifest, mapOf(bundleFile.hash to jarContent))

        val bootstrap = createBootstrap()
        val result = bootstrap.validate()
        assertIs<BundleValidationResult.Valid>(result)

        val loadedBundle = bootstrap.launch(result)
        try {
            assertNotNull(loadedBundle.sendMessage)
            // Should not throw — exception is swallowed
            loadedBundle.sendMessage.invoke("test message")
        } finally {
            loadedBundle.mainThread.join(5000)
            loadedBundle.close()
        }
    }

    // ----- JAR creation helpers -----

    /**
     * Creates a JAR with a class that has:
     * - static main(String[]) that returns immediately
     * - static onShellMessage(String) that sets System.setProperty("shell-message-bridge-test", msg)
     */
    private fun createJarWithStaticOnShellMessage(className: String): Path {
        val jarPath = tempDir.resolve("${className.replace('/', '-')}.jar")
        val classBytes = buildClassWithStaticOnShellMessage(className)

        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            jos.putNextEntry(JarEntry("$className.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }

        return jarPath
    }

    /**
     * Creates a JAR with a class that has only static main(String[]).
     */
    private fun createJarWithStaticMain(className: String): Path {
        val jarPath = tempDir.resolve("${className.replace('/', '-')}.jar")
        val classBytes = buildClassWithStaticMain(className)

        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            jos.putNextEntry(JarEntry("$className.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }

        return jarPath
    }

    /**
     * Creates a JAR with a class that has static main and non-static onShellMessage.
     */
    private fun createJarWithNonStaticOnShellMessage(className: String): Path {
        val jarPath = tempDir.resolve("${className.replace('/', '-')}.jar")
        val classBytes = buildClassWithNonStaticOnShellMessage(className)

        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            jos.putNextEntry(JarEntry("$className.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }

        return jarPath
    }

    /**
     * Creates a JAR with a class that has static main and static onShellMessage that throws.
     */
    private fun createJarWithThrowingOnShellMessage(className: String): Path {
        val jarPath = tempDir.resolve("${className.replace('/', '-')}.jar")
        val classBytes = buildClassWithThrowingOnShellMessage(className)

        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            jos.putNextEntry(JarEntry("$className.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }

        return jarPath
    }

    // ----- Bytecode generation helpers -----

    /**
     * Builds a class with:
     * - public static void main(String[]) { }
     * - public static void onShellMessage(String msg) { System.setProperty("shell-message-bridge-test", msg); }
     */
    private fun buildClassWithStaticOnShellMessage(className: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // Magic
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        // Version (Java 8)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count = 19
        baos.write(shortBytes(19))

        // #1: CONSTANT_Class -> #2 (this class)
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(2))
        // #2: CONSTANT_Utf8 -> className
        writeUtf8(baos, className)
        // #3: CONSTANT_Class -> #4 (java/lang/Object)
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(4))
        // #4: CONSTANT_Utf8 -> java/lang/Object
        writeUtf8(baos, "java/lang/Object")
        // #5: CONSTANT_Utf8 -> main
        writeUtf8(baos, "main")
        // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
        writeUtf8(baos, "([Ljava/lang/String;)V")
        // #7: CONSTANT_Utf8 -> Code
        writeUtf8(baos, "Code")
        // #8: CONSTANT_Utf8 -> onShellMessage
        writeUtf8(baos, "onShellMessage")
        // #9: CONSTANT_Utf8 -> (Ljava/lang/String;)V
        writeUtf8(baos, "(Ljava/lang/String;)V")
        // #10: CONSTANT_Class -> #11 (java/lang/System)
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(11))
        // #11: CONSTANT_Utf8 -> java/lang/System
        writeUtf8(baos, "java/lang/System")
        // #12: CONSTANT_Utf8 -> setProperty
        writeUtf8(baos, "setProperty")
        // #13: CONSTANT_Utf8 -> (Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
        writeUtf8(baos, "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
        // #14: CONSTANT_NameAndType -> #12:#13
        baos.write(byteArrayOf(0x0C)); baos.write(shortBytes(12)); baos.write(shortBytes(13))
        // #15: CONSTANT_Methodref -> #10.#14 (System.setProperty)
        baos.write(byteArrayOf(0x0A)); baos.write(shortBytes(10)); baos.write(shortBytes(14))
        // #16: CONSTANT_Utf8 -> shell-message-bridge-test
        writeUtf8(baos, "shell-message-bridge-test")
        // #17: CONSTANT_String -> #16
        baos.write(byteArrayOf(0x08)); baos.write(shortBytes(16))
        // #18: CONSTANT_Utf8 -> <init>
        writeUtf8(baos, "<init>")

        // Access flags: public (0x0021)
        baos.write(shortBytes(0x0021))
        // This class (#1)
        baos.write(shortBytes(1))
        // Super class (#3)
        baos.write(shortBytes(3))
        // Interfaces count (0)
        baos.write(shortBytes(0))
        // Fields count (0)
        baos.write(shortBytes(0))

        // Methods count (2: main and onShellMessage)
        baos.write(shortBytes(2))

        // Method 1: public static void main(String[])
        baos.write(shortBytes(0x0009)) // public static
        baos.write(shortBytes(5)) // name: main
        baos.write(shortBytes(6)) // descriptor: ([Ljava/lang/String;)V
        baos.write(shortBytes(1)) // 1 attribute
        // Code attribute
        baos.write(shortBytes(7)) // attribute name: Code
        baos.write(intBytes(13)) // attribute length
        baos.write(shortBytes(0)) // max_stack
        baos.write(shortBytes(1)) // max_locals
        baos.write(intBytes(1)) // code length
        baos.write(byteArrayOf(0xB1.toByte())) // return
        baos.write(shortBytes(0)) // exception table length
        baos.write(shortBytes(0)) // code attributes count

        // Method 2: public static void onShellMessage(String)
        // Calls System.setProperty("shell-message-bridge-test", msg)
        baos.write(shortBytes(0x0009)) // public static
        baos.write(shortBytes(8)) // name: onShellMessage
        baos.write(shortBytes(9)) // descriptor: (Ljava/lang/String;)V
        baos.write(shortBytes(1)) // 1 attribute
        // Code attribute
        baos.write(shortBytes(7)) // attribute name: Code
        // Code: ldc "shell-message-bridge-test", aload_0, invokestatic System.setProperty, pop, return
        val codeBytes = byteArrayOf(
            0x12, 17, // ldc #17 (string constant "shell-message-bridge-test")
            0x2A,     // aload_0 (the msg parameter)
            0xB8.toByte(), 0x00, 15, // invokestatic #15 (System.setProperty)
            0x57,     // pop (discard return value)
            0xB1.toByte(), // return
        )
        baos.write(intBytes(12 + codeBytes.size)) // attribute length
        baos.write(shortBytes(2)) // max_stack
        baos.write(shortBytes(1)) // max_locals
        baos.write(intBytes(codeBytes.size)) // code length
        baos.write(codeBytes)
        baos.write(shortBytes(0)) // exception table length
        baos.write(shortBytes(0)) // code attributes count

        // Class attributes count (0)
        baos.write(shortBytes(0))

        return baos.toByteArray()
    }

    /**
     * Builds a class with only: public static void main(String[]) { }
     */
    private fun buildClassWithStaticMain(className: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // Magic
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        // Version
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count = 8
        baos.write(shortBytes(8))
        // #1: CONSTANT_Class -> #2
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(2))
        // #2: CONSTANT_Utf8 -> className
        writeUtf8(baos, className)
        // #3: CONSTANT_Class -> #4
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(4))
        // #4: CONSTANT_Utf8 -> java/lang/Object
        writeUtf8(baos, "java/lang/Object")
        // #5: CONSTANT_Utf8 -> main
        writeUtf8(baos, "main")
        // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
        writeUtf8(baos, "([Ljava/lang/String;)V")
        // #7: CONSTANT_Utf8 -> Code
        writeUtf8(baos, "Code")

        // Access flags, this class, super class
        baos.write(shortBytes(0x0021))
        baos.write(shortBytes(1))
        baos.write(shortBytes(3))
        // Interfaces, fields
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Methods count (1)
        baos.write(shortBytes(1))

        // Method: public static void main(String[])
        baos.write(shortBytes(0x0009))
        baos.write(shortBytes(5))
        baos.write(shortBytes(6))
        baos.write(shortBytes(1))
        baos.write(shortBytes(7))
        baos.write(intBytes(13))
        baos.write(shortBytes(0))
        baos.write(shortBytes(1))
        baos.write(intBytes(1))
        baos.write(byteArrayOf(0xB1.toByte()))
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Class attributes
        baos.write(shortBytes(0))

        return baos.toByteArray()
    }

    /**
     * Builds a class with:
     * - public static void main(String[]) { }
     * - public void onShellMessage(String) { } (NOT static)
     */
    private fun buildClassWithNonStaticOnShellMessage(className: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // Magic + version
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count = 10
        baos.write(shortBytes(10))
        // #1: CONSTANT_Class -> #2
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(2))
        // #2: CONSTANT_Utf8 -> className
        writeUtf8(baos, className)
        // #3: CONSTANT_Class -> #4
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(4))
        // #4: CONSTANT_Utf8 -> java/lang/Object
        writeUtf8(baos, "java/lang/Object")
        // #5: CONSTANT_Utf8 -> main
        writeUtf8(baos, "main")
        // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
        writeUtf8(baos, "([Ljava/lang/String;)V")
        // #7: CONSTANT_Utf8 -> Code
        writeUtf8(baos, "Code")
        // #8: CONSTANT_Utf8 -> onShellMessage
        writeUtf8(baos, "onShellMessage")
        // #9: CONSTANT_Utf8 -> (Ljava/lang/String;)V
        writeUtf8(baos, "(Ljava/lang/String;)V")

        // Access flags, this, super
        baos.write(shortBytes(0x0021))
        baos.write(shortBytes(1))
        baos.write(shortBytes(3))
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Methods count (2)
        baos.write(shortBytes(2))

        // Method 1: public static void main(String[])
        baos.write(shortBytes(0x0009))
        baos.write(shortBytes(5))
        baos.write(shortBytes(6))
        baos.write(shortBytes(1))
        baos.write(shortBytes(7))
        baos.write(intBytes(13))
        baos.write(shortBytes(0))
        baos.write(shortBytes(1))
        baos.write(intBytes(1))
        baos.write(byteArrayOf(0xB1.toByte()))
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Method 2: public void onShellMessage(String) — NOT static (0x0001 = public only)
        baos.write(shortBytes(0x0001))
        baos.write(shortBytes(8))
        baos.write(shortBytes(9))
        baos.write(shortBytes(1))
        baos.write(shortBytes(7))
        baos.write(intBytes(13))
        baos.write(shortBytes(0))
        baos.write(shortBytes(2))
        baos.write(intBytes(1))
        baos.write(byteArrayOf(0xB1.toByte()))
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Class attributes
        baos.write(shortBytes(0))

        return baos.toByteArray()
    }

    /**
     * Builds a class with:
     * - public static void main(String[]) { }
     * - public static void onShellMessage(String) { throw new RuntimeException(); }
     */
    private fun buildClassWithThrowingOnShellMessage(className: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // Magic + version
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count = 15
        baos.write(shortBytes(15))
        // #1: CONSTANT_Class -> #2
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(2))
        // #2: CONSTANT_Utf8 -> className
        writeUtf8(baos, className)
        // #3: CONSTANT_Class -> #4
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(4))
        // #4: CONSTANT_Utf8 -> java/lang/Object
        writeUtf8(baos, "java/lang/Object")
        // #5: CONSTANT_Utf8 -> main
        writeUtf8(baos, "main")
        // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
        writeUtf8(baos, "([Ljava/lang/String;)V")
        // #7: CONSTANT_Utf8 -> Code
        writeUtf8(baos, "Code")
        // #8: CONSTANT_Utf8 -> onShellMessage
        writeUtf8(baos, "onShellMessage")
        // #9: CONSTANT_Utf8 -> (Ljava/lang/String;)V
        writeUtf8(baos, "(Ljava/lang/String;)V")
        // #10: CONSTANT_Class -> #11 (RuntimeException)
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(11))
        // #11: CONSTANT_Utf8 -> java/lang/RuntimeException
        writeUtf8(baos, "java/lang/RuntimeException")
        // #12: CONSTANT_Utf8 -> <init>
        writeUtf8(baos, "<init>")
        // #13: CONSTANT_Utf8 -> ()V
        writeUtf8(baos, "()V")
        // #14: CONSTANT_NameAndType -> #12:#13 (<init>:()V)
        baos.write(byteArrayOf(0x0C)); baos.write(shortBytes(12)); baos.write(shortBytes(13))
        // Oops, need a Methodref for RuntimeException.<init>
        // Let me re-count: need constant pool count = 16

        // Start over with correct count...
        return buildClassWithThrowingOnShellMessageV2(className)
    }

    private fun buildClassWithThrowingOnShellMessageV2(className: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // Magic + version
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count = 16
        baos.write(shortBytes(16))
        // #1: CONSTANT_Class -> #2
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(2))
        // #2: CONSTANT_Utf8 -> className
        writeUtf8(baos, className)
        // #3: CONSTANT_Class -> #4
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(4))
        // #4: CONSTANT_Utf8 -> java/lang/Object
        writeUtf8(baos, "java/lang/Object")
        // #5: CONSTANT_Utf8 -> main
        writeUtf8(baos, "main")
        // #6: CONSTANT_Utf8 -> ([Ljava/lang/String;)V
        writeUtf8(baos, "([Ljava/lang/String;)V")
        // #7: CONSTANT_Utf8 -> Code
        writeUtf8(baos, "Code")
        // #8: CONSTANT_Utf8 -> onShellMessage
        writeUtf8(baos, "onShellMessage")
        // #9: CONSTANT_Utf8 -> (Ljava/lang/String;)V
        writeUtf8(baos, "(Ljava/lang/String;)V")
        // #10: CONSTANT_Class -> #11 (RuntimeException)
        baos.write(byteArrayOf(0x07)); baos.write(shortBytes(11))
        // #11: CONSTANT_Utf8 -> java/lang/RuntimeException
        writeUtf8(baos, "java/lang/RuntimeException")
        // #12: CONSTANT_Utf8 -> <init>
        writeUtf8(baos, "<init>")
        // #13: CONSTANT_Utf8 -> ()V
        writeUtf8(baos, "()V")
        // #14: CONSTANT_NameAndType -> #12:#13 (<init>:()V)
        baos.write(byteArrayOf(0x0C)); baos.write(shortBytes(12)); baos.write(shortBytes(13))
        // #15: CONSTANT_Methodref -> #10.#14 (RuntimeException.<init>)
        baos.write(byteArrayOf(0x0A)); baos.write(shortBytes(10)); baos.write(shortBytes(14))

        // Access flags, this, super
        baos.write(shortBytes(0x0021))
        baos.write(shortBytes(1))
        baos.write(shortBytes(3))
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Methods count (2)
        baos.write(shortBytes(2))

        // Method 1: public static void main(String[])
        baos.write(shortBytes(0x0009))
        baos.write(shortBytes(5))
        baos.write(shortBytes(6))
        baos.write(shortBytes(1))
        baos.write(shortBytes(7))
        baos.write(intBytes(13))
        baos.write(shortBytes(0))
        baos.write(shortBytes(1))
        baos.write(intBytes(1))
        baos.write(byteArrayOf(0xB1.toByte()))
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Method 2: public static void onShellMessage(String) — throws RuntimeException
        baos.write(shortBytes(0x0009))
        baos.write(shortBytes(8))
        baos.write(shortBytes(9))
        baos.write(shortBytes(1))
        baos.write(shortBytes(7))
        // Code: new RuntimeException, dup, invokespecial <init>, athrow
        val codeBytes = byteArrayOf(
            0xBB.toByte(), 0x00, 10, // new #10 (RuntimeException)
            0x59,                     // dup
            0xB7.toByte(), 0x00, 15, // invokespecial #15 (RuntimeException.<init>)
            0xBF.toByte(),           // athrow
        )
        baos.write(intBytes(12 + codeBytes.size))
        baos.write(shortBytes(2)) // max_stack
        baos.write(shortBytes(1)) // max_locals
        baos.write(intBytes(codeBytes.size))
        baos.write(codeBytes)
        baos.write(shortBytes(0))
        baos.write(shortBytes(0))

        // Class attributes
        baos.write(shortBytes(0))

        return baos.toByteArray()
    }

    // ----- Low-level bytecode helpers -----

    private fun shortBytes(value: Int): ByteArray =
        byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun intBytes(value: Int): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun writeUtf8(baos: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray()
        baos.write(byteArrayOf(0x01))
        baos.write(shortBytes(bytes.size))
        baos.write(bytes)
    }

    // ----- Test infrastructure -----

    private fun createBootstrap(): BundleBootstrap {
        val config = BundleBootstrapConfig(
            appDataDir = appDataDir,
            bundleSubdirectory = "",
            baseUrl = "https://example.com",
            publicKey = keyPair.publicKeyBase64,
            shellVersion = 100,
            platform = Platform.fromString(platformStr),
            mainClass = "io.runwork.TestMain",
        )
        return BundleBootstrap(config)
    }

    private fun setupBundle(manifest: BundleManifest, files: Map<BundleFileHash, ByteArray>) {
        val manifestPath = appDataDir.resolve("manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest))

        val casDir = appDataDir.resolve("cas")
        Files.createDirectories(casDir)
        val versionDir = appDataDir.resolve("versions/${manifest.buildNumber}")
        Files.createDirectories(versionDir)

        for (bundleFile in manifest.files) {
            val content = files[bundleFile.hash] ?: continue
            val casFile = casDir.resolve(bundleFile.hash.hex)
            Files.write(casFile, content)

            val versionFile = versionDir.resolve(bundleFile.path)
            Files.createDirectories(versionFile.parent)
            when (Os.current) {
                Os.WINDOWS -> Files.createLink(versionFile, casFile)
                Os.MACOS, Os.LINUX -> {
                    val relativeSource = versionFile.parent.relativize(casFile)
                    Files.createSymbolicLink(versionFile, relativeSource)
                }
            }
        }
    }

    private fun createBundleFile(path: String, content: ByteArray): BundleFile {
        val hash = HashVerifier.computeHash(content)
        return BundleFile(path = path, hash = hash, size = content.size.toLong())
    }

    private fun createSignedManifest(
        files: List<BundleFile>,
        buildNumber: Long = 1,
        mainClass: String = "io.runwork.TestMain",
        shellMessageHandlerClass: String? = null,
    ): BundleManifest {
        val unsigned = BundleManifest(
            schemaVersion = 1,
            buildNumber = buildNumber,
            createdAt = "2025-01-01T00:00:00Z",
            minShellVersion = 1,
            files = files,
            mainClass = mainClass,
            zips = mapOf(
                platformStr to PlatformBundle(
                    zip = "zips/bundle-$platformStr.zip",
                    size = files.sumOf { it.size }
                )
            ),
            shellMessageHandlerClass = shellMessageHandlerClass,
            signature = ""
        )

        val jsonBytes = BundleJson.signingJson.encodeToString(unsigned).toByteArray()
        val signature = keyPair.signer.sign(jsonBytes)
        return unsigned.copy(signature = "ed25519:$signature")
    }

    // ----- Key pair helpers -----

    data class TestKeyPair(
        val signer: TestSigner,
        val publicKeyBase64: String,
    )

    class TestSigner(private val privateKeyBytes: ByteArray) {
        fun sign(data: ByteArray): String {
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            val signature = Signature.getInstance("Ed25519")
            signature.initSign(privateKey)
            signature.update(data)
            return Base64.getEncoder().encodeToString(signature.sign())
        }
    }

    private fun generateTestKeyPair(): TestKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val kp = keyPairGenerator.generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        return TestKeyPair(TestSigner(kp.private.encoded), publicKeyBase64)
    }
}
