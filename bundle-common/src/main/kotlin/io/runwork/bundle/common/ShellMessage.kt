package io.runwork.bundle.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Messages sent from the shell to the bundle via `onShellMessage(String)`.
 *
 * Serialized as JSON with a `type` discriminator field.
 */
@Serializable
sealed class ShellMessage {
    /**
     * Notifies the bundle that a new version has been downloaded and is ready to apply.
     *
     * The bundle can call [restartProcess] when ready to apply the update.
     */
    @Serializable
    @SerialName("UpdateReady")
    data class UpdateReady(
        val newBuildNumber: Long,
        val currentBuildNumber: Long,
    ) : ShellMessage()

    companion object {
        private val json = Json {
            classDiscriminator = "type"
        }

        fun encode(message: ShellMessage): String = json.encodeToString(serializer(), message)

        fun decode(jsonString: String): ShellMessage = json.decodeFromString(serializer(), jsonString)
    }
}
