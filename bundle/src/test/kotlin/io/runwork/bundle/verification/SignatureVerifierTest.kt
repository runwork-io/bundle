package io.runwork.bundle.verification

import io.runwork.bundle.TestFixtures
import io.runwork.bundle.manifest.BundleFile
import io.runwork.bundle.manifest.FileType
import io.runwork.bundle.manifest.ManifestSigner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureVerifierTest {

    @Test
    fun verifyManifest_returnsTrueForValidSignature() {
        val (signer, verifier) = TestFixtures.generateTestKeyPair()

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    size = 1000,
                    type = FileType.JAR
                )
            ),
            signer = signer
        )

        val result = verifier.verifyManifest(manifest)

        assertTrue(result)
    }

    @Test
    fun verifyManifest_returnsFalseForInvalidSignature() {
        val (signer1, _) = TestFixtures.generateTestKeyPair()
        val (_, verifier2) = TestFixtures.generateTestKeyPair() // Different key pair

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    size = 1000,
                    type = FileType.JAR
                )
            ),
            signer = signer1
        )

        // Verify with wrong key
        val result = verifier2.verifyManifest(manifest)

        assertFalse(result)
    }

    @Test
    fun verifyManifest_returnsFalseForTamperedManifest() {
        val (signer, verifier) = TestFixtures.generateTestKeyPair()

        val manifest = TestFixtures.createSignedManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    size = 1000,
                    type = FileType.JAR
                )
            ),
            signer = signer,
            buildNumber = 1
        )

        // Tamper with the manifest by changing build number
        val tamperedManifest = manifest.copy(buildNumber = 2)

        val result = verifier.verifyManifest(tamperedManifest)

        assertFalse(result)
    }

    @Test
    fun verifyManifest_returnsFalseForEmptySignature() {
        val (_, verifier) = TestFixtures.generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    size = 1000,
                    type = FileType.JAR
                )
            )
        )
        // Manifest has empty signature by default

        val result = verifier.verifyManifest(manifest)

        assertFalse(result)
    }

    @Test
    fun verifyManifest_returnsFalseForMalformedSignature() {
        val (_, verifier) = TestFixtures.generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    size = 1000,
                    type = FileType.JAR
                )
            )
        ).copy(signature = "ed25519:not-valid-base64!!!")

        val result = verifier.verifyManifest(manifest)

        assertFalse(result)
    }

    @Test
    fun verify_data_returnsTrueForValidSignature() {
        val (signer, verifier) = TestFixtures.generateTestKeyPair()

        val data = "Hello, World!".toByteArray()
        val signature = signer.sign(data)

        val result = verifier.verify(data, signature)

        assertTrue(result)
    }

    @Test
    fun verify_data_returnsFalseForWrongData() {
        val (signer, verifier) = TestFixtures.generateTestKeyPair()

        val data = "Hello, World!".toByteArray()
        val signature = signer.sign(data)

        val result = verifier.verify("Wrong data".toByteArray(), signature)

        assertFalse(result)
    }

    @Test
    fun verify_data_worksWithAndWithoutPrefix() {
        val (signer, verifier) = TestFixtures.generateTestKeyPair()

        val data = "Test data".toByteArray()
        val signature = signer.sign(data)

        // With prefix
        assertTrue(verifier.verify(data, "ed25519:$signature"))

        // Without prefix
        assertTrue(verifier.verify(data, signature))
    }
}
