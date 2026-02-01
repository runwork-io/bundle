package io.runwork.bundle.common.verification

import io.runwork.bundle.common.manifest.BundleManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
     * Verify a manifest's signature.
     *
     * The signature is verified against the manifest JSON with the signature field set to empty.
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
            val dataToVerify = Json.encodeToString(manifestWithoutSig).toByteArray(Charsets.UTF_8)

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
