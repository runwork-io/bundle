package io.runwork.bundle.loader

import io.runwork.bundle.TestFixtures
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class BundleClassLoaderTest {

    private lateinit var tempDir: Path
    private lateinit var bundleClassLoader: BundleClassLoader

    @BeforeTest
    fun setUp() {
        tempDir = TestFixtures.createTempDir("classloader-test")
    }

    @AfterTest
    fun tearDown() {
        if (::bundleClassLoader.isInitialized) {
            bundleClassLoader.close()
        }
        TestFixtures.deleteRecursively(tempDir)
    }

    @Test
    fun loadClass_delegatesToParentForJdk() {
        bundleClassLoader = BundleClassLoader(emptyArray(), ClassLoader.getSystemClassLoader())

        // java.* classes should come from parent
        val stringClass = bundleClassLoader.loadClass("java.lang.String")

        assertEquals(String::class.java, stringClass)
    }

    @Test
    fun loadClass_delegatesToParentForJavax() {
        bundleClassLoader = BundleClassLoader(emptyArray(), ClassLoader.getSystemClassLoader())

        // javax.* classes should come from parent
        val clazz = bundleClassLoader.loadClass("javax.swing.JFrame")

        assertNotNull(clazz)
    }

    @Test
    fun loadClass_fallsBackToParentWhenNotInBundle() {
        bundleClassLoader = BundleClassLoader(emptyArray(), ClassLoader.getSystemClassLoader())

        // A class that's not in bundle should fall back to parent
        // Using StringBuilder which is always available in JDK
        val stringBuilderClass = bundleClassLoader.loadClass("java.lang.StringBuilder")

        assertNotNull(stringBuilderClass)
        assertEquals(StringBuilder::class.java, stringBuilderClass)
    }

    @Test
    fun loadClass_cachesLoadedClasses() {
        bundleClassLoader = BundleClassLoader(emptyArray(), ClassLoader.getSystemClassLoader())

        val class1 = bundleClassLoader.loadClass("java.lang.String")
        val class2 = bundleClassLoader.loadClass("java.lang.String")

        assertSame(class1, class2)
    }

    @Test
    fun getResource_loadsFromBundleFirst() {
        // Create a JAR with a resource
        val jarPath = createTestJarWithResource("test-resource.txt", "bundle content")

        bundleClassLoader = BundleClassLoader(
            arrayOf(jarPath.toUri().toURL()),
            ClassLoader.getSystemClassLoader()
        )

        val resource = bundleClassLoader.getResource("test-resource.txt")

        assertNotNull(resource)
        assertTrue(resource.toString().contains("test-resource.txt"))
    }

    @Test
    fun getResource_returnsNullForMissingResource() {
        bundleClassLoader = BundleClassLoader(emptyArray(), ClassLoader.getSystemClassLoader())

        val resource = bundleClassLoader.getResource("definitely-does-not-exist-12345.txt")

        assertNull(resource)
    }

    @Test
    fun getResources_combinesBundleAndParent() {
        // Create a JAR with a resource
        val jarPath = createTestJarWithResource("META-INF/test.txt", "bundle content")

        bundleClassLoader = BundleClassLoader(
            arrayOf(jarPath.toUri().toURL()),
            ClassLoader.getSystemClassLoader()
        )

        val resources = bundleClassLoader.getResources("META-INF/test.txt").toList()

        // Should have at least the bundle resource
        assertTrue(resources.isNotEmpty())
    }

    @Test
    fun loadClass_loadsFromBundleFirst() {
        // Create a JAR with a class
        val jarPath = createTestJarWithClass()

        bundleClassLoader = BundleClassLoader(
            arrayOf(jarPath.toUri().toURL()),
            ClassLoader.getSystemClassLoader()
        )

        val testClass = bundleClassLoader.loadClass("com.test.TestClass")

        assertNotNull(testClass)
        // Class should be loaded from bundle classloader
        assertEquals(bundleClassLoader, testClass.classLoader)
    }

    @Test
    fun parentPackages_areCorrect() {
        bundleClassLoader = BundleClassLoader(emptyArray(), ClassLoader.getSystemClassLoader())

        // These should all go to parent
        val javaClass = bundleClassLoader.loadClass("java.util.ArrayList")
        val javaxClass = bundleClassLoader.loadClass("javax.swing.JButton")

        // java and javax should come from bootstrap/system loader
        assertNotNull(javaClass)
        assertNotNull(javaxClass)
    }

    private fun createTestJarWithResource(resourceName: String, content: String): Path {
        val jarPath = tempDir.resolve("test.jar")

        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            jos.putNextEntry(JarEntry(resourceName))
            jos.write(content.toByteArray())
            jos.closeEntry()
        }

        return jarPath
    }

    private fun createTestJarWithClass(): Path {
        val jarPath = tempDir.resolve("test-class.jar")

        // Minimal class file bytes for com.test.TestClass
        // This is a simple compiled empty class
        val classBytes = createMinimalClassBytes("com/test/TestClass")

        JarOutputStream(Files.newOutputStream(jarPath)).use { jos ->
            jos.putNextEntry(JarEntry("com/test/TestClass.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }

        return jarPath
    }

    /**
     * Creates minimal bytecode for an empty class.
     */
    private fun createMinimalClassBytes(className: String): ByteArray {
        val baos = ByteArrayOutputStream()

        // Magic number
        baos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        // Version (Java 8: major=52, minor=0)
        baos.write(byteArrayOf(0x00, 0x00, 0x00, 0x34))

        // Constant pool count (7 entries, 1-indexed so count=8)
        baos.write(byteArrayOf(0x00, 0x08))

        // Constant pool entries:

        // #1: CONSTANT_Class -> #2
        baos.write(byteArrayOf(0x07, 0x00, 0x02))

        // #2: CONSTANT_Utf8 -> class name
        val classNameBytes = className.toByteArray()
        baos.write(byteArrayOf(0x01, (classNameBytes.size shr 8).toByte(), classNameBytes.size.toByte()))
        baos.write(classNameBytes)

        // #3: CONSTANT_Class -> #4 (java/lang/Object)
        baos.write(byteArrayOf(0x07, 0x00, 0x04))

        // #4: CONSTANT_Utf8 -> java/lang/Object
        val objectName = "java/lang/Object".toByteArray()
        baos.write(byteArrayOf(0x01, (objectName.size shr 8).toByte(), objectName.size.toByte()))
        baos.write(objectName)

        // #5: CONSTANT_Utf8 -> <init>
        val initName = "<init>".toByteArray()
        baos.write(byteArrayOf(0x01, (initName.size shr 8).toByte(), initName.size.toByte()))
        baos.write(initName)

        // #6: CONSTANT_Utf8 -> ()V
        val voidDescriptor = "()V".toByteArray()
        baos.write(byteArrayOf(0x01, (voidDescriptor.size shr 8).toByte(), voidDescriptor.size.toByte()))
        baos.write(voidDescriptor)

        // #7: CONSTANT_Utf8 -> Code
        val codeName = "Code".toByteArray()
        baos.write(byteArrayOf(0x01, (codeName.size shr 8).toByte(), codeName.size.toByte()))
        baos.write(codeName)

        // Access flags (public)
        baos.write(byteArrayOf(0x00, 0x21))

        // This class (#1)
        baos.write(byteArrayOf(0x00, 0x01))

        // Super class (#3)
        baos.write(byteArrayOf(0x00, 0x03))

        // Interfaces count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        // Fields count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        // Methods count (0 - no constructor needed for loading test)
        baos.write(byteArrayOf(0x00, 0x00))

        // Attributes count (0)
        baos.write(byteArrayOf(0x00, 0x00))

        return baos.toByteArray()
    }
}
