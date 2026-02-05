package io.runwork.bundle.common.manifest

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = BundleFileHashSerializer::class)
data class BundleFileHash(
    val algorithm: String,
    val hex: String,
) {
    override fun toString(): String = "$algorithm:$hex"

    companion object {
        fun parse(hash: String): BundleFileHash {
            val colonIndex = hash.indexOf(':')
            require(colonIndex > 0) { "Invalid hash format, expected 'algorithm:hex': $hash" }
            val hex = hash.substring(colonIndex + 1)
            require(hex.isNotEmpty()) { "Invalid hash format, hex value is empty: $hash" }
            return BundleFileHash(
                algorithm = hash.substring(0, colonIndex),
                hex = hex,
            )
        }
    }
}

object BundleFileHashSerializer : KSerializer<BundleFileHash> {
    override val descriptor = PrimitiveSerialDescriptor("BundleFileHash", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BundleFileHash) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): BundleFileHash = BundleFileHash.parse(decoder.decodeString())
}
