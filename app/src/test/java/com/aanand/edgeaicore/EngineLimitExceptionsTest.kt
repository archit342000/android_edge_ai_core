package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLimitExceptionsTest {
    @Test
    fun theMessagesAreFixed() {
        assertEquals("Server busy", EngineBusyException().message)
        assertEquals("Generation timed out", GenerationTimeoutException().message)
        assertEquals("Model not loaded", ModelNotLoadedException().message)
    }

    @Test
    fun modelNotLoadedIsAnIllegalStateException() {
        // Callers that catch IllegalStateException keep working
        val e: Throwable = ModelNotLoadedException()
        assertTrue(e is IllegalStateException)
    }
}
