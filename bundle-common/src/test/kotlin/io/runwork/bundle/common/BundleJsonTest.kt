package io.runwork.bundle.common

import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleManifest
import io.runwork.bundle.common.manifest.PlatformBundle
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden tests that lock down the exact JSON encoding produced by [BundleJson.signingJson].
 *
 * Any change to field order, default-omission behavior, or formatting will break these
 * tests — which is exactly the point: the signing format must never drift silently.
 */
class BundleJsonTest {

    @Test
    fun encodeFullManifest_matchesGoldenJson() {
        val manifest = BundleManifest(
            schemaVersion = 1,
            buildNumber = 42,
            createdAt = "2025-06-15T12:00:00Z",
            minShellVersion = 3,
            shellUpdateUrl = "https://example.com/update",
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:aaaa",
                    size = 1024,
                ),
                BundleFile(
                    path = "natives/libfoo.dylib",
                    hash = "sha256:bbbb",
                    size = 2048,
                    os = Os.MACOS,
                    arch = Arch.ARM64,
                ),
            ),
            mainClass = "com.example.MainKt",
            platformBundles = mapOf(
                "macos-arm64" to PlatformBundle(zip = "bundle-macos-arm64.zip", size = 3072),
                "linux-x64" to PlatformBundle(zip = "bundle-linux-x64.zip", size = 4096),
            ),
            signature = "", // default → omitted by encodeDefaults = false
        )

        val expected = """{"schemaVersion":1,"buildNumber":42,"createdAt":"2025-06-15T12:00:00Z","minShellVersion":3,"shellUpdateUrl":"https://example.com/update","files":[{"path":"app.jar","hash":"sha256:aaaa","size":1024},{"path":"natives/libfoo.dylib","hash":"sha256:bbbb","size":2048,"os":"macos","arch":"arm64"}],"mainClass":"com.example.MainKt","platformBundles":{"macos-arm64":{"zip":"bundle-macos-arm64.zip","size":3072},"linux-x64":{"zip":"bundle-linux-x64.zip","size":4096}}}"""

        val actual = BundleJson.signingJson.encodeToString(manifest)

        assertEquals(expected, actual)
    }

    @Test
    fun encodeManifestWithDefaults_omitsNullAndDefaultFields() {
        val manifest = BundleManifest(
            schemaVersion = 1,
            buildNumber = 1,
            createdAt = "2025-01-01T00:00:00Z",
            minShellVersion = 1,
            shellUpdateUrl = null, // default → omitted
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:cccc",
                    size = 512,
                    os = null,  // default → omitted
                    arch = null, // default → omitted
                ),
            ),
            mainClass = "io.runwork.TestMain",
            platformBundles = mapOf(
                "macos-arm64" to PlatformBundle(zip = "bundle-macos-arm64.zip", size = 512),
            ),
            signature = "", // default → omitted
        )

        val expected = """{"schemaVersion":1,"buildNumber":1,"createdAt":"2025-01-01T00:00:00Z","minShellVersion":1,"files":[{"path":"app.jar","hash":"sha256:cccc","size":512}],"mainClass":"io.runwork.TestMain","platformBundles":{"macos-arm64":{"zip":"bundle-macos-arm64.zip","size":512}}}"""

        val actual = BundleJson.signingJson.encodeToString(manifest)

        assertEquals(expected, actual)
    }
}
