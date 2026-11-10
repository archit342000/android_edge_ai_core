package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineLimitExceptionsTest {
    @Test
    fun theMessagesAreFixed() {
        assertEquals("Server busy", EngineBusyException().message)
        assertEquals("Generation timed out", GenerationTimeoutException().message)
    }
}
