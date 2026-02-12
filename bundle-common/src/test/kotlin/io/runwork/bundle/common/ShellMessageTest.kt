package io.runwork.bundle.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShellMessageTest {

    @Test
    fun encode_updateReady_producesExpectedJson() {
        val message = ShellMessage.UpdateReady(
            newBuildNumber = 42,
            currentBuildNumber = 41,
        )

        val json = ShellMessage.encode(message)

        assertEquals(
            """{"type":"UpdateReady","newBuildNumber":42,"currentBuildNumber":41}""",
            json,
        )
    }

    @Test
    fun decode_updateReady_roundTrips() {
        val original = ShellMessage.UpdateReady(
            newBuildNumber = 100,
            currentBuildNumber = 99,
        )

        val json = ShellMessage.encode(original)
        val decoded = ShellMessage.decode(json)

        assertIs<ShellMessage.UpdateReady>(decoded)
        assertEquals(100, decoded.newBuildNumber)
        assertEquals(99, decoded.currentBuildNumber)
    }

    @Test
    fun decode_fromRawJson() {
        val json = """{"type":"UpdateReady","newBuildNumber":5,"currentBuildNumber":4}"""

        val decoded = ShellMessage.decode(json)

        assertIs<ShellMessage.UpdateReady>(decoded)
        assertEquals(5, decoded.newBuildNumber)
        assertEquals(4, decoded.currentBuildNumber)
    }
}
