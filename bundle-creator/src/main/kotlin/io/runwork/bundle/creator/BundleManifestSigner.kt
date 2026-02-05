package io.runwork.bundle.creator

import io.runwork.bundle.common.BundleJson
import io.runwork.bundle.common.manifest.BundleManifest
import kotlinx.serialization.encodeToString
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Signs bundle manifests with Ed25519.
 *
 * Uses JDK's built-in Ed25519 support (available in JDK 15+).
 */
class BundleManifestSigner(
    private val privateKey: PrivateKey
) {
    companion object {
        /**
         * Create a ManifestSigner from a Base64-encoded private key.
         *
         * @param privateKeyBase64 PKCS#8-encoded private key in Base64
         */
        fun fromBase64(privateKeyBase64: String): BundleManifestSigner {
            val keyBytes = Base64.getDecoder().decode(privateKeyBase64)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val privateKey = keyFactory.generatePrivate(keySpec)
            return BundleManifestSigner(privateKey)
        }

        /**
         * Generate a new Ed25519 key pair.
         *
         * @return Pair of (privateKeyBase64, publicKeyBase64)
         */
        fun generateKeyPair(): Pair<String, String> {
            val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
            val keyPair = keyPairGenerator.generateKeyPair()

            val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
            val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

            return Pair(privateKeyBase64, publicKeyBase64)
        }
    }

    /**
     * Sign a manifest and return a new manifest with the signature field set.
     *
     * @param manifest The manifest to sign (signature field will be ignored)
     * @return A new manifest with the signature field populated
     */
    fun signManifest(manifest: BundleManifest): BundleManifest {
        // Create manifest without signature for signing
        val manifestWithoutSig = manifest.copy(signature = "")
        val dataToSign = BundleJson.signingJson.encodeToString(manifestWithoutSig).toByteArray(Charsets.UTF_8)

        val signatureBase64 = sign(dataToSign)
        return manifest.copy(signature = "ed25519:$signatureBase64")
    }

    /**
     * Sign arbitrary data and return the Base64-encoded signature.
     *
     * @param data The data to sign
     * @return Base64-encoded Ed25519 signature
     */
    fun sign(data: ByteArray): String {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(data)
        val signatureBytes = signature.sign()
        return Base64.getEncoder().encodeToString(signatureBytes)
    }
}
