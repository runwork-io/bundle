package io.runwork.bundle.common.manifest

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = BundleFileHashSerializer::class)
data class BundleFileHash(
    val type: String,
    val value: String,
) {
    override fun toString(): String = "$type:$value"

    companion object {
        fun parse(hash: String): BundleFileHash {
            val colonIndex = hash.indexOf(':')
            require(colonIndex > 0) { "Invalid hash format, expected 'type:value': $hash" }
            return BundleFileHash(
                type = hash.substring(0, colonIndex),
                value = hash.substring(colonIndex + 1),
            )
        }
    }
}

object BundleFileHashSerializer : KSerializer<BundleFileHash> {
    override val descriptor = PrimitiveSerialDescriptor("BundleFileHash", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BundleFileHash) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): BundleFileHash = BundleFileHash.parse(decoder.decodeString())
}
