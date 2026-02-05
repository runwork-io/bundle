package io.runwork.bundle.common.verification

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.manifest.BundleManifest
import kotlinx.serialization.encodeToString
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies Ed25519 signatures on bundle manifests.
 *
 * Uses JDK's built-in Ed25519 support (available in JDK 15+).
 */
class SignatureVerifier(
    publicKeyBase64: String
) {
    private val publicKey: PublicKey

    init {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("Ed25519")
        publicKey = keyFactory.generatePublic(keySpec)
    }

    /**
     * Verify a manifest's signature against the raw JSON string.
     *
     * This approach is forward-compatible: if a future creator adds new fields to the
     * manifest, older clients can still verify the signature because they verify against
     * the original JSON bytes rather than round-tripping through the Kotlin data class
     * (which would drop unknown fields).
     *
     * The fast path handles compact JSON where the signature is the last field:
     * `{...,"signature":"ed25519:ABC=="}`
     * It strips the signature field via string manipulation and verifies against the rest.
     *
     * The fallback handles legacy pretty-printed manifests (no unknown keys) by
     * round-tripping through the Kotlin data class.
     *
     * @param rawJson The raw manifest JSON string as read from disk or network
     * @return true if the signature is valid, false otherwise
     */
    fun verifyManifestJson(rawJson: String): Boolean {
        // Fast path: compact JSON with signature as last field
        val sigFieldPrefix = ",\"signature\":\""
        val sigFieldStart = rawJson.lastIndexOf(sigFieldPrefix)
        if (sigFieldStart != -1 && rawJson.endsWith("\"}")) {
            val signatureValue = rawJson.substring(
                sigFieldStart + sigFieldPrefix.length,
                rawJson.length - 2 // strip trailing "}
            )
            val signedJson = rawJson.substring(0, sigFieldStart) + "}"
            if (verify(signedJson.toByteArray(Charsets.UTF_8), signatureValue)) {
                return true
            }
            // Fast path didn't verify (e.g., JSON was written with different encoding
            // settings than used for signing). Fall through to round-trip fallback.
        }

        // Fallback: round-trip through Kotlin data class.
        // Works for legacy pretty-printed manifests and compact manifests written with
        // different JSON settings, as long as all keys are known to BundleManifest.
        return try {
            val manifest = BundleJson.decodingJson.decodeFromString<BundleManifest>(rawJson)
            verifyManifest(manifest)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verify a manifest's signature by round-tripping through the Kotlin data class.
     *
     * Note: This is NOT forward-compatible with unknown fields. Prefer [verifyManifestJson]
     * for verification of manifests that may contain fields not yet in this client's
     * [BundleManifest] data class.
     *
     * @param manifest The manifest to verify
     * @return true if the signature is valid, false otherwise
     */
    fun verifyManifest(manifest: BundleManifest): Boolean {
        val signatureStr = manifest.signature.removePrefix("ed25519:")
        if (signatureStr.isEmpty()) return false

        return try {
            val signatureBytes = Base64.getDecoder().decode(signatureStr)

            // Serialize manifest without signature for verification
            val manifestWithoutSig = manifest.copy(signature = "")
            val dataToVerify = BundleJson.signingJson.encodeToString(manifestWithoutSig).toByteArray(Charsets.UTF_8)

            val signature = Signature.getInstance("Ed25519")
            signature.initVerify(publicKey)
            signature.update(dataToVerify)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verify a signature on arbitrary data.
     *
     * @param data The data that was signed
     * @param signatureBase64 The Base64-encoded signature (with or without "ed25519:" prefix)
     * @return true if the signature is valid, false otherwise
     */
    fun verify(data: ByteArray, signatureBase64: String): Boolean {
        val signatureStr = signatureBase64.removePrefix("ed25519:")
        if (signatureStr.isEmpty()) return false

        return try {
            val signatureBytes = Base64.getDecoder().decode(signatureStr)

            val signature = Signature.getInstance("Ed25519")
            signature.initVerify(publicKey)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
}
