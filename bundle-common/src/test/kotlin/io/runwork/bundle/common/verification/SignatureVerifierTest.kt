package io.runwork.bundle.common.verification

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.TestFixtures
import io.runwork.bundle.common.manifest.BundleFile
import io.runwork.bundle.common.manifest.BundleFileHash
import io.runwork.bundle.common.manifest.BundleManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.security.KeyPairGenerator
import java.util.Base64

class SignatureVerifierTest {

    /**
     * Generate a test key pair for signing/verification.
     */
    private fun generateTestKeyPair(): Pair<ByteArray, SignatureVerifier> {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKeyBytes = keyPair.private.encoded
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return Pair(privateKeyBytes, SignatureVerifier(publicKeyBase64))
    }

    /**
     * Sign data with private key.
     */
    private fun sign(privateKeyBytes: ByteArray, data: ByteArray): String {
        val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
        val privateKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes))
        val signature = java.security.Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(data)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    @Test
    fun verify_data_returnsTrueForValidSignature() {
        val (privateKey, verifier) = generateTestKeyPair()

        val data = "Hello, World!".toByteArray()
        val signature = sign(privateKey, data)

        val result = verifier.verify(data, signature)

        assertTrue(result)
    }

    @Test
    fun verify_data_returnsFalseForWrongData() {
        val (privateKey, verifier) = generateTestKeyPair()

        val data = "Hello, World!".toByteArray()
        val signature = sign(privateKey, data)

        val result = verifier.verify("Wrong data".toByteArray(), signature)

        assertFalse(result)
    }

    @Test
    fun verify_data_worksWithAndWithoutPrefix() {
        val (privateKey, verifier) = generateTestKeyPair()

        val data = "Test data".toByteArray()
        val signature = sign(privateKey, data)

        // With prefix
        assertTrue(verifier.verify(data, "ed25519:$signature"))

        // Without prefix
        assertTrue(verifier.verify(data, signature))
    }

    @Test
    fun verifyManifest_returnsFalseForEmptySignature() {
        val (_, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )
        // Manifest has empty signature by default

        val result = verifier.verifyManifest(manifest)

        assertFalse(result)
    }

    @Test
    fun verifyManifest_returnsFalseForMalformedSignature() {
        val (_, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        ).copy(signature = "ed25519:not-valid-base64!!!")

        val result = verifier.verifyManifest(manifest)

        assertFalse(result)
    }

    @Test
    fun verifyManifest_returnsTrueForValidSignature() {
        val (privateKey, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )

        // Sign the manifest (must match how SignatureVerifier verifies)
        val dataToSign = BundleJson.signingJson.encodeToString(
            kotlinx.serialization.serializer<io.runwork.bundle.common.manifest.BundleManifest>(),
            manifest
        ).toByteArray(Charsets.UTF_8)
        val signature = sign(privateKey, dataToSign)
        val signedManifest = manifest.copy(signature = "ed25519:$signature")

        val result = verifier.verifyManifest(signedManifest)

        assertTrue(result)
    }

    @Test
    fun verifyManifest_returnsFalseWithWrongKey() {
        val (privateKey, _) = generateTestKeyPair()
        val (_, differentVerifier) = generateTestKeyPair() // Different key pair

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )

        // Sign with one key
        val dataToSign = BundleJson.signingJson.encodeToString(
            kotlinx.serialization.serializer<io.runwork.bundle.common.manifest.BundleManifest>(),
            manifest
        ).toByteArray(Charsets.UTF_8)
        val signature = sign(privateKey, dataToSign)
        val signedManifest = manifest.copy(signature = "ed25519:$signature")

        // Verify with different key - should fail
        val result = differentVerifier.verifyManifest(signedManifest)

        assertFalse(result)
    }

    @Test
    fun verifyManifest_returnsFalseWhenManifestModified() {
        val (privateKey, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            ),
            buildNumber = 1
        )

        // Sign the original manifest
        val dataToSign = BundleJson.signingJson.encodeToString(
            kotlinx.serialization.serializer<io.runwork.bundle.common.manifest.BundleManifest>(),
            manifest
        ).toByteArray(Charsets.UTF_8)
        val signature = sign(privateKey, dataToSign)

        // Apply signature then modify the manifest
        val tamperedManifest = manifest.copy(
            signature = "ed25519:$signature",
            buildNumber = 999 // Modified after signing!
        )

        val result = verifier.verifyManifest(tamperedManifest)

        assertFalse(result)
    }

    // ====== verifyManifestJson tests ======

    @Test
    fun verifyManifestJson_compactManifestWithValidSignature() {
        val (privateKey, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )

        // Sign and produce compact JSON (same as BundleCreatorTask would)
        val signingJson = BundleJson.signingJson
        val unsignedJson = signingJson.encodeToString(manifest)
        val signature = sign(privateKey, unsignedJson.toByteArray(Charsets.UTF_8))
        val signedManifest = manifest.copy(signature = "ed25519:$signature")
        val compactJson = signingJson.encodeToString(signedManifest)

        assertTrue(verifier.verifyManifestJson(compactJson))
    }

    @Test
    fun verifyManifestJson_compactManifestWithUnknownKeysAndValidSignature() {
        val (privateKey, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )

        // Simulate a future creator that adds an unknown field and signs the full
        // unsigned JSON (including the unknown field). An older client must still
        // verify successfully despite not recognizing "futureField".
        val signingJson = BundleJson.signingJson
        val unsignedJson = signingJson.encodeToString(manifest)
        val unsignedWithExtra = unsignedJson.dropLast(1) + ",\"futureField\":\"hello\"}"
        val signature = sign(privateKey, unsignedWithExtra.toByteArray(Charsets.UTF_8))
        val distributionJson = unsignedWithExtra.dropLast(1) + ",\"signature\":\"ed25519:$signature\"}"

        assertTrue(verifier.verifyManifestJson(distributionJson))
    }

    @Test
    fun verifyManifestJson_compactManifestWithTamperedData() {
        val (privateKey, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )

        // Sign and produce compact JSON
        val signingJson = BundleJson.signingJson
        val unsignedJson = signingJson.encodeToString(manifest)
        val signature = sign(privateKey, unsignedJson.toByteArray(Charsets.UTF_8))
        val signedManifest = manifest.copy(signature = "ed25519:$signature")
        val compactJson = signingJson.encodeToString(signedManifest)

        // Tamper: change buildNumber from 1 to 9
        val tampered = compactJson.replace("\"buildNumber\":1", "\"buildNumber\":9")

        assertFalse(verifier.verifyManifestJson(tampered))
    }

    @Test
    fun verifyManifestJson_prettyPrintedLegacyManifestWithValidSignature() {
        val (privateKey, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )

        // Sign using the signing JSON (compact)
        val signingJson = BundleJson.signingJson
        val unsignedJson = signingJson.encodeToString(manifest)
        val signature = sign(privateKey, unsignedJson.toByteArray(Charsets.UTF_8))
        val signedManifest = manifest.copy(signature = "ed25519:$signature")

        // Write as pretty-printed JSON (legacy format)
        val prettyJson = Json { prettyPrint = true }
        val prettyPrintedJson = prettyJson.encodeToString(signedManifest)

        // The fast path won't match (not compact), so it should fall back to
        // round-trip verification through the Kotlin data class.
        assertTrue(verifier.verifyManifestJson(prettyPrintedJson))
    }

    @Test
    fun verifyManifestJson_returnsFalseForEmptySignature() {
        val (_, verifier) = generateTestKeyPair()

        val manifest = TestFixtures.createTestManifest(
            files = listOf(
                BundleFile(
                    path = "app.jar",
                    hash = BundleFileHash.parse("sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                    size = 1000,
                )
            )
        )

        // Compact JSON with no signature field (encodeDefaults=false omits signature="")
        val signingJson = BundleJson.signingJson
        val jsonWithoutSig = signingJson.encodeToString(manifest)

        assertFalse(verifier.verifyManifestJson(jsonWithoutSig))
    }
}
