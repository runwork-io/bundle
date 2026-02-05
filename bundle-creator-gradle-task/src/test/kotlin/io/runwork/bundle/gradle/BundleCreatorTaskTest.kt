package io.runwork.bundle.gradle

import io.runwork.bundle.common.Platform
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.verification.SignatureVerifier
import io.runwork.bundle.creator.BundleManifestSigner
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundleCreatorTaskTest {

    private lateinit var testProjectDir: Path
    private lateinit var buildFile: File
    private lateinit var inputDir: File
    private lateinit var privateKeyFile: File
    private lateinit var publicKey: String
    private lateinit var pluginClasspath: List<File>
    private lateinit var classpathString: String

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        testProjectDir = Files.createTempDirectory("gradle-task-test")
        buildFile = testProjectDir.resolve("build.gradle.kts").toFile()
        inputDir = testProjectDir.resolve("input").toFile().also { it.mkdirs() }

        // Generate key pair
        val (privateKey, pubKey) = BundleManifestSigner.generateKeyPair()
        publicKey = pubKey
        privateKeyFile = testProjectDir.resolve("private.key").toFile()
        privateKeyFile.writeText(privateKey)

        // Load plugin classpath from generated file
        val classpathFile = File("build/pluginClasspath/plugin-classpath.txt")
        pluginClasspath = if (classpathFile.exists()) {
            classpathFile.readLines().filter { it.isNotBlank() }.map { File(it) }
        } else {
            // Fallback: use the runtime classpath from system property
            System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it) }
        }

        // Generate classpath string for buildscript (single files() call with multiple paths)
        val pathsList = pluginClasspath.joinToString(",\n                    ") {
            "\"${it.absolutePath.replace("\\", "/")}\""
        }
        classpathString = "classpath(files(\n                    $pathsList\n                ))"

        // Create settings.gradle.kts
        testProjectDir.resolve("settings.gradle.kts").toFile().writeText(
            """
            rootProject.name = "test-project"
            """.trimIndent()
        )
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(testProjectDir)
    }

    private fun writeBuildFile(taskConfig: String) {
        buildFile.writeText(
            """
            buildscript {
                repositories {
                    mavenCentral()
                }
                dependencies {
                    $classpathString
                }
            }

            import io.runwork.bundle.gradle.BundleCreatorTask

            plugins {
                kotlin("jvm") version "2.1.0"
            }

            $taskConfig
            """.trimIndent()
        )
    }

    @Test
    fun taskCreatesManifestBundleAndFiles() {
        // Create input files
        File(inputDir, "test.txt").writeText("Test content")
        File(inputDir, "subdir").mkdirs()
        File(inputDir, "subdir/nested.txt").writeText("Nested content")

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("macos-arm64"))
                buildNumber.set(12345L)
                privateKeyFile.set(file("private.key"))
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":createBundle")?.outcome)

        val outputDir = testProjectDir.resolve("build/bundle").toFile()

        // Verify manifest exists and is properly structured
        val manifestFile = File(outputDir, "manifest.json")
        assertTrue(manifestFile.exists(), "manifest.json should exist")

        val manifest = json.decodeFromString<BundleManifest>(manifestFile.readText())
        assertEquals(12345L, manifest.buildNumber)
        assertTrue(manifest.supportsPlatform(Platform.fromString("macos-arm64")))
        assertEquals("com.test.MainKt", manifest.mainClass)
        assertTrue(manifest.signature.startsWith("ed25519:"))
        assertEquals(2, manifest.files.size)

        // Verify signature is valid
        val verifier = SignatureVerifier(publicKey)
        assertTrue(verifier.verifyManifest(manifest), "Manifest signature should be valid")

        // Verify bundle zip exists and contains files (now content-addressed)
        val bundleZipName = manifest.platformBundles["macos-arm64"]?.bundleZip
        assertNotNull(bundleZipName, "Platform bundle should exist for macos-arm64")
        val bundleZip = File(outputDir, bundleZipName)
        assertTrue(bundleZip.exists(), "$bundleZipName should exist")

        ZipFile(bundleZip).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertTrue(entries.contains("test.txt"))
            assertTrue(entries.contains("subdir/nested.txt"))
        }

        // Verify files/ directory exists with content-addressable files
        val filesDir = File(outputDir, "files")
        assertTrue(filesDir.exists(), "files/ directory should exist")
        assertTrue(filesDir.isDirectory)

        // Each manifest file should have a corresponding hash-named file
        for (bundleFile in manifest.files) {
            val hashFileName = bundleFile.hash.removePrefix("sha256:")
            val hashedFile = File(filesDir, hashFileName)
            assertTrue(hashedFile.exists(), "File ${bundleFile.path} should exist as $hashFileName")
        }
    }

    @Test
    fun taskUsesPrivateKeyFromEnvVar() {
        File(inputDir, "test.txt").writeText("Content")

        // Generate a separate key pair for this test
        val (envPrivateKey, envPublicKey) = BundleManifestSigner.generateKeyPair()

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("macos-arm64"))
                buildNumber.set(1L)
                privateKeyEnvVar.set("TEST_BUNDLE_KEY")
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .withEnvironment(mapOf("TEST_BUNDLE_KEY" to envPrivateKey))
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":createBundle")?.outcome)

        val manifestFile = testProjectDir.resolve("build/bundle/manifest.json").toFile()
        val manifest = json.decodeFromString<BundleManifest>(manifestFile.readText())

        // Verify signature with the env key's public key
        val verifier = SignatureVerifier(envPublicKey)
        assertTrue(verifier.verifyManifest(manifest))
    }

    @Test
    fun taskUsesPrivateKeyDirectly() {
        File(inputDir, "test.txt").writeText("Content")

        // Generate a separate key pair for this test
        val (directPrivateKey, directPublicKey) = BundleManifestSigner.generateKeyPair()

        // Write the private key to a properties file to simulate providers.gradleProperty()
        testProjectDir.resolve("gradle.properties").toFile().writeText(
            "bundlePrivateKey=$directPrivateKey"
        )

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("macos-arm64"))
                buildNumber.set(1L)
                // Use the privateKey property directly with a provider
                privateKey.set(providers.gradleProperty("bundlePrivateKey"))
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":createBundle")?.outcome)

        val manifestFile = testProjectDir.resolve("build/bundle/manifest.json").toFile()
        val manifest = json.decodeFromString<BundleManifest>(manifestFile.readText())

        // Verify signature with the direct key's public key
        val verifier = SignatureVerifier(directPublicKey)
        assertTrue(verifier.verifyManifest(manifest))
    }

    @Test
    fun taskSetsOptionalProperties() {
        File(inputDir, "test.txt").writeText("Content")

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("linux-x64"))
                buildNumber.set(999L)
                minShellVersion.set(5)
                shellUpdateUrl.set("https://example.com/update")
                privateKeyFile.set(file("private.key"))
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":createBundle")?.outcome)

        val manifestFile = testProjectDir.resolve("build/bundle/manifest.json").toFile()
        val manifest = json.decodeFromString<BundleManifest>(manifestFile.readText())

        assertTrue(manifest.supportsPlatform(Platform.fromString("linux-x64")))
        assertEquals(999L, manifest.buildNumber)
        assertEquals(5, manifest.minShellVersion)
        assertEquals("https://example.com/update", manifest.shellUpdateUrl)
    }

    @Test
    fun taskFailsWithoutPrivateKey() {
        File(inputDir, "test.txt").writeText("Content")

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("macos-arm64"))
                buildNumber.set(1L)
                // No private key set
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .buildAndFail()

        assertTrue(result.output.contains("privateKey, privateKeyEnvVar, or privateKeyFile"))
    }

    @Test
    fun taskFailsWithInvalidPlatform() {
        File(inputDir, "test.txt").writeText("Content")

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("invalid-platform"))
                buildNumber.set(1L)
                privateKeyFile.set(file("private.key"))
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .buildAndFail()

        assertTrue(result.output.contains("Invalid platform 'invalid-platform'"))
    }

    @Test
    fun generateKeyPairReturnsValidKeys() {
        val (privateKey, publicKey) = BundleCreatorTask.generateKeyPair()

        // Keys should be non-empty Base64 strings
        assertTrue(privateKey.isNotEmpty())
        assertTrue(publicKey.isNotEmpty())

        // Keys should be usable for signing and verification
        val signer = BundleManifestSigner.fromBase64(privateKey)
        val verifier = SignatureVerifier(publicKey)

        val data = "Test data".toByteArray()
        val signature = signer.sign(data)
        assertTrue(verifier.verify(data, signature))
    }

    @Test
    fun taskCalculatesCorrectTotalSize() {
        val content1 = "A".repeat(100)
        val content2 = "B".repeat(200)
        File(inputDir, "file1.txt").writeText(content1)
        File(inputDir, "file2.txt").writeText(content2)

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("macos-arm64"))
                buildNumber.set(1L)
                privateKeyFile.set(file("private.key"))
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":createBundle")?.outcome)

        val manifestFile = testProjectDir.resolve("build/bundle/manifest.json").toFile()
        val manifest = json.decodeFromString<BundleManifest>(manifestFile.readText())

        // totalSize should equal the actual zip file size on disk, not the sum of file sizes
        val bundleZipName = manifest.platformBundles["macos-arm64"]!!.bundleZip
        val bundleZipFile = testProjectDir.resolve("build/bundle/$bundleZipName").toFile()
        assertEquals(bundleZipFile.length(), manifest.sizeForPlatform(Platform.fromString("macos-arm64")))
    }

    @Test
    fun taskNormalizesPathsToForwardSlashes() {
        File(inputDir, "subdir/nested").mkdirs()
        File(inputDir, "subdir/nested/file.txt").writeText("Content")

        writeBuildFile(
            """
            tasks.register<BundleCreatorTask>("createBundle") {
                inputDirectory.set(file("input"))
                outputDirectory.set(layout.buildDirectory.dir("bundle"))
                mainClass.set("com.test.MainKt")
                platforms.set(listOf("macos-arm64"))
                buildNumber.set(1L)
                privateKeyFile.set(file("private.key"))
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withArguments("createBundle", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":createBundle")?.outcome)

        val manifestFile = testProjectDir.resolve("build/bundle/manifest.json").toFile()
        val manifest = json.decodeFromString<BundleManifest>(manifestFile.readText())

        val nestedFile = manifest.files.find { it.path.contains("file.txt") }
        assertNotNull(nestedFile)
        assertEquals("subdir/nested/file.txt", nestedFile.path)
        assertTrue(!nestedFile.path.contains("\\"))
    }

    private fun deleteRecursively(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.delete(it) }
        }
    }
}
