package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.LiteRtLmJniException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ModelInspectorTest {

    private fun capabilities(kind: ModelKind = ModelKind.LLM, text: Boolean = true) =
        ModelCapabilities(kind, supportsText = text, supportsVision = false, supportsAudio = false)

    @Test
    fun anLlmWithTextInputIsAccepted() {
        ModelInspector.validate(capabilities())
    }

    @Test
    fun nonLlmAndNoTextModelsAreRejected() {
        for (bad in listOf(capabilities(kind = ModelKind.EMBEDDING), capabilities(kind = ModelKind.UNKNOWN), capabilities(text = false))) {
            try {
                ModelInspector.validate(bad)
                fail("expected UnsupportedModelException for $bad")
            } catch (e: UnsupportedModelException) {
                // expected
            }
        }
    }

    @Test
    fun inspectMapsTheRawValues() {
        val inspector = ModelInspector { RawModelInfo(ModelKind.LLM, text = true, vision = true, audio = false) }
        assertEquals(
            ModelCapabilities(ModelKind.LLM, supportsText = true, supportsVision = true, supportsAudio = false),
            inspector.inspect("/any/path")
        )
    }

    @Test
    fun aNativeReadFailureBecomesUnsupportedModelWithTheCause() {
        val cause = LiteRtLmJniException("bad header")
        val inspector = ModelInspector { throw cause }
        try {
            inspector.inspect("/any/path")
            fail("expected UnsupportedModelException")
        } catch (e: UnsupportedModelException) {
            assertSame(cause, e.cause)
        }
    }

    @Test
    fun otherExceptionsAreNotWrapped() {
        val inspector = ModelInspector { throw IllegalStateException("closed") }
        try {
            inspector.inspect("/any/path")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("closed", e.message)
        }
    }
}
