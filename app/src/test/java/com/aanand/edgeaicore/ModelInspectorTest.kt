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
            ModelCapabilities(
                ModelKind.LLM, supportsText = true, supportsVision = true, supportsAudio = false,
                // no backends recorded by the model: normalised to CPU and GPU; no audio encoder: null
                visionBackends = listOf(com.google.ai.edge.litertlm.BackendType.CPU, com.google.ai.edge.litertlm.BackendType.GPU)
            ),
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

    @Test
    fun speculativeDecodingIsTrueOnlyForAnLlmWithADrafter() {
        fun caps(kind: ModelKind, drafter: Boolean) =
            ModelInspector { RawModelInfo(kind, text = true, vision = false, audio = false, speculativeSupport = drafter) }.inspect("/p")
        assertEquals(true, caps(ModelKind.LLM, drafter = true).speculativeDecoding)
        assertEquals(false, caps(ModelKind.LLM, drafter = false).speculativeDecoding)
        assertEquals(false, caps(ModelKind.EMBEDDING, drafter = true).speculativeDecoding)
        assertEquals(false, caps(ModelKind.UNKNOWN, drafter = true).speculativeDecoding)
    }

    @Test
    fun encoderBackendsComeFromTheModelOnlyWhenTheEncoderExists() {
        fun caps(vision: Boolean, audio: Boolean, visionBackends: List<com.google.ai.edge.litertlm.BackendType>, audioBackends: List<com.google.ai.edge.litertlm.BackendType> = emptyList()) =
            ModelInspector { RawModelInfo(ModelKind.LLM, true, vision, audio, visionBackends = visionBackends, audioBackends = audioBackends) }.inspect("/p")
        val textOnly = caps(false, false, listOf(com.google.ai.edge.litertlm.BackendType.GPU))
        assertEquals(null, textOnly.visionBackends)
        assertEquals(null, textOnly.audioBackends)
        val gpuOnly = caps(true, true, listOf(com.google.ai.edge.litertlm.BackendType.GPU), listOf(com.google.ai.edge.litertlm.BackendType.CPU))
        assertEquals(listOf(com.google.ai.edge.litertlm.BackendType.GPU), gpuOnly.visionBackends)
        assertEquals(listOf(com.google.ai.edge.litertlm.BackendType.CPU), gpuOnly.audioBackends)
        val unconstrained = caps(true, false, emptyList())
        assertEquals(listOf(com.google.ai.edge.litertlm.BackendType.CPU, com.google.ai.edge.litertlm.BackendType.GPU), unconstrained.visionBackends)
        assertEquals(null, caps(true, false, listOf(com.google.ai.edge.litertlm.BackendType.NPU)).visionBackends)
    }
}
