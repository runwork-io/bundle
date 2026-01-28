package io.runwork.bundle.manifest

import io.runwork.bundle.TestFixtures
import io.runwork.bundle.verification.SignatureVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ManifestSignerTest {

    @Test
    fun generateKeyPair_producesValidKeys() {
        val (privateKey, publicKey) = ManifestSigner.generateKeyPair()

        // Both keys should be non-empty Base64 strings
        assertTrue(privateKey.isNotEmpty())
        assertTrue(publicKey.isNotEmpty())

        // Should be valid Base64
        java.util.Base64.getDecoder().decode(privateKey)
        java.util.Base64.getDecoder().decode(publicKey)

        // Keys should work for signing and verification
        val signer = ManifestSigner.fromBase64(privateKey)
        val verifier = SignatureVerifier(publicKey)

        val data = "Test data".toByteArray()
        val signature = signer.sign(data)

        assertTrue(verifier.verify(data, signature))
    }

    @Test
    fun generateKeyPair_producesUniqueKeys() {
        val (privateKey1, publicKey1) = ManifestSigner.generateKeyPair()
        val (privateKey2, publicKey2) = ManifestSigner.generateKeyPair()

        // Different key pairs should have different keys
        assertNotEquals(privateKey1, privateKey2)
        assertNotEquals(publicKey1, publicKey2)
    }

    @Test
    fun fromBase64_loadsValidKey() {
        val (privateKey, publicKey) = ManifestSigner.generateKeyPair()

        val signer = ManifestSigner.fromBase64(privateKey)

        // Should be able to sign data
        val signature = signer.sign("test".toByteArray())
        assertTrue(signature.isNotEmpty())

        // Signature should verify
        val verifier = SignatureVerifier(publicKey)
        assertTrue(verifier.verify("test".toByteArray(), signature))
    }

    @Test
    fun fromBase64_throwsForInvalidKey() {
        val invalidKey = "not-a-valid-base64-key!!!"

        assertFailsWith<IllegalArgumentException> {
            ManifestSigner.fromBase64(invalidKey)
        }
    }

    @Test
    fun fromBase64_throwsForWrongKeyType() {
        // This is valid Base64 but not a valid Ed25519 private key
        val wrongKey = java.util.Base64.getEncoder().encodeToString("wrong key data".toByteArray())

        assertFailsWith<Exception> {
            ManifestSigner.fromBase64(wrongKey)
        }
    }

    @Test
    fun signManifest_producesValidSignature() {
        val (signer, verifier) = TestFixtures.generateTestKeyPair()

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

        val signedManifest = signer.signManifest(manifest)

        // Signature should be present and valid
        assertTrue(signedManifest.signature.startsWith("ed25519:"))
        assertTrue(verifier.verifyManifest(signedManifest))
    }

    @Test
    fun signManifest_preservesManifestData() {
        val (signer, _) = TestFixtures.generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    size = 1000,
                    type = FileType.JAR
                )
            ),
            buildNumber = 42,
            platform = "macos-arm64",
            mainClass = "io.runwork.Main"
        )

        val signedManifest = signer.signManifest(manifest)

        // All fields should be preserved
        assertEquals(manifest.schemaVersion, signedManifest.schemaVersion)
        assertEquals(manifest.buildNumber, signedManifest.buildNumber)
        assertEquals(manifest.platform, signedManifest.platform)
        assertEquals(manifest.createdAt, signedManifest.createdAt)
        assertEquals(manifest.minimumShellVersion, signedManifest.minimumShellVersion)
        assertEquals(manifest.files, signedManifest.files)
        assertEquals(manifest.mainClass, signedManifest.mainClass)
        assertEquals(manifest.totalSize, signedManifest.totalSize)
        assertEquals(manifest.bundleHash, signedManifest.bundleHash)
        // Only signature should be different
        assertNotEquals(manifest.signature, signedManifest.signature)
    }

    @Test
    fun signManifest_overwritesExistingSignature() {
        val (signer, verifier) = TestFixtures.generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = emptyList()
        ).copy(signature = "ed25519:old-signature")

        val signedManifest = signer.signManifest(manifest)

        // New signature should be different and valid
        assertNotEquals("ed25519:old-signature", signedManifest.signature)
        assertTrue(verifier.verifyManifest(signedManifest))
    }

    @Test
    fun sign_producesConsistentSignature() {
        val (signer, _) = TestFixtures.generateTestKeyPair()

        val data = "Consistent data".toByteArray()

        val signature1 = signer.sign(data)
        val signature2 = signer.sign(data)

        // Ed25519 is deterministic, same input = same output
        assertEquals(signature1, signature2)
    }

    @Test
    fun sign_producesDifferentSignaturesForDifferentData() {
        val (signer, _) = TestFixtures.generateTestKeyPair()

        val signature1 = signer.sign("Data 1".toByteArray())
        val signature2 = signer.sign("Data 2".toByteArray())

        assertNotEquals(signature1, signature2)
    }

    @Test
    fun signManifest_differentManifestsProduceDifferentSignatures() {
        val (signer, _) = TestFixtures.generateTestKeyPair()

        val manifest1 = TestFixtures.createTestManifest(files = emptyList(), buildNumber = 1)
        val manifest2 = TestFixtures.createTestManifest(files = emptyList(), buildNumber = 2)

        val signed1 = signer.signManifest(manifest1)
        val signed2 = signer.signManifest(manifest2)

        assertNotEquals(signed1.signature, signed2.signature)
    }
}
