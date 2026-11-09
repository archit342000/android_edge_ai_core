package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Backend
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class AiEngineManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun modelFile(): String = tmp.newFile("model.litertlm").absolutePath

    private fun state(id: String = "c1") = ConversationState(conversationId = id, apiToken = "t", ttlMs = 0)

    private fun userMessage(text: String) = ChatMessage("user", JsonPrimitive(text))

    @Test
    fun loadModelThrowsWhenFileIsMissing() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        try {
            manager.loadModel(File(tmp.root, "missing.litertlm").absolutePath, "CPU")
            fail("expected FileNotFoundException")
        } catch (e: FileNotFoundException) {
            assertFalse(manager.isModelLoaded)
            assertTrue(factory.configs.isEmpty())
        }
    }

    @Test
    fun loadModelRetriesOnCpuWhenGpuFails() = runTest {
        val factory = FakeEngineFactory(failOnGpu = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "GPU")
        assertEquals(2, factory.configs.size)
        assertTrue(factory.configs[0].backend is Backend.GPU)
        assertTrue(factory.configs[1].backend is Backend.CPU)
        assertTrue(manager.isModelLoaded)
    }

    @Test
    fun backendNameMapsToBackendType() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        val path = modelFile()
        manager.loadModel(path, "npu")
        manager.close()
        manager.loadModel(path, "bogus")
        assertTrue(factory.configs[0].backend is Backend.NPU)
        assertTrue(factory.configs[1].backend is Backend.GPU)
    }

    @Test
    fun loadModelRethrowsCpuFailure() = runTest {
        val manager = newManager({ throw IllegalStateException("boom") })
        try {
            manager.loadModel(modelFile(), "CPU")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
            assertFalse(manager.isModelLoaded)
            assertNull(manager.modelCapabilities) // inspected fine, but the engine failed: nothing is "loaded"
        }
    }

    @Test
    fun generateStreamsTokensAndCompletesOnce() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")

        val tokens = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val state = state()
        manager.generateConversationResponseAsync(
            state, listOf(userMessage("hi")),
            onToken = { tokens.add(it) },
            onComplete = { text, _ -> completed.add(text) },
            onErrorCallback = { errors.add(it) }
        )

        assertEquals(listOf("Hel", "lo"), tokens)
        assertEquals(listOf("Hello"), completed)
        assertTrue(errors.isEmpty())
        assertEquals("assistant", state.history.last().role)
    }

    @Test
    fun generateReportsCallbackErrorOnce() = runTest {
        val factory = FakeEngineFactory(failure = RuntimeException("engine failed"))
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")

        var completes = 0
        val errors = mutableListOf<Throwable>()
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("hi")),
            onToken = {},
            onComplete = { _, _ -> completes++ },
            onErrorCallback = { errors.add(it) }
        )

        assertEquals(0, completes)
        assertEquals(1, errors.size)
        assertEquals("engine failed", errors[0].message)
    }

    @Test
    fun generateWithoutModelThrowsIllegalState() = runTest {
        val manager = newManager(FakeEngineFactory())
        try {
            manager.generateConversationResponseAsync(
                state(), listOf(userMessage("hi")),
                onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
            )
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Model not loaded", e.message)
        }
    }

    @Test
    fun emptyMessagesReportAnError() = runTest {
        val manager = newManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "CPU")
        val errors = mutableListOf<Throwable>()
        manager.generateConversationResponseAsync(
            state(), emptyList(),
            onToken = {}, onComplete = { _, _ -> }, onErrorCallback = { errors.add(it) }
        )
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalArgumentException)
    }

    @Test
    fun sameConversationWithOneNewMessageReusesConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val state = state()

        repeat(2) { i ->
            manager.generateConversationResponseAsync(
                state, listOf(userMessage("m$i")),
                onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
            )
        }

        assertEquals(1, factory.engine.conversations.size)
        assertEquals(2, factory.engine.conversations[0].sent)
    }

    @Test
    fun changedSamplingParamsRebuildConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val state = state()

        manager.generateConversationResponseAsync(
            state, listOf(userMessage("a")), onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
        )
        state.temperature = 0.1
        manager.generateConversationResponseAsync(
            state, listOf(userMessage("b")), onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
        )

        assertEquals(2, factory.engine.conversations.size)
        assertTrue(factory.engine.conversations[0].closed)
    }

    @Test
    fun closeReleasesEngineAndConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("a")), onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
        )

        manager.close()

        assertFalse(manager.isModelLoaded)
        assertTrue(factory.engine.closed)
        assertTrue(factory.engine.conversations[0].closed)
    }

    @Test
    fun completionCarriesTheTokenUsageFromTheEngineCounts() = runTest {
        // 2 of the 12 tokens in the KV cache were there before this generation
        val manager = newManager(FakeEngineFactory(counts = Triple(7, 3, 12)))
        manager.loadModel(modelFile(), "CPU")
        var usage: TokenUsage? = null
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("hi")), onToken = {}, onComplete = { _, u -> usage = u },
            onErrorCallback = { fail("unexpected ${it.message}") }
        )
        assertEquals(TokenUsage(promptTokens = 9, completionTokens = 3, cachedTokens = 2), usage)
    }

    @Test
    fun unreadableUsageIsAnErrorNotZeros() = runTest {
        val factory = FakeEngineFactory(usageFailure = IllegalStateException("benchmark off"))
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val s = state()
        var completes = 0
        val errors = mutableListOf<Throwable>()
        manager.generateConversationResponseAsync(
            s, listOf(userMessage("hi")), onToken = {}, onComplete = { _, _ -> completes++ }, onErrorCallback = { errors.add(it) }
        )
        assertEquals(0, completes)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is UsageUnavailableException)
        assertEquals(1, s.history.size) // only the user message; no assistant reply was appended
        assertTrue(factory.engine.conversations[0].closed) // the engine holds a reply the history lacks

        // The same conversation id is rebuilt, not reused.
        manager.generateConversationResponseAsync(
            s, listOf(userMessage("again")), onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
        )
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun benchmarkIsEnabledWhenTheEngineIsCreatedAlsoOnTheCpuRetry() = runTest {
        @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
        com.google.ai.edge.litertlm.ExperimentalFlags.enableBenchmark = false // it is process-wide; start from off
        val factory = FakeEngineFactory(failOnGpu = true)
        newManager(factory).loadModel(modelFile(), "GPU")
        assertEquals(listOf(true, true), factory.benchmarkFlagAtCreation)
    }

    @Test
    fun anUnsupportedModelIsRejectedBeforeAnyEngineIsCreated() = runTest {
        val path = modelFile()
        for (backend in listOf("CPU", "GPU")) {
            val factory = FakeEngineFactory()
            val manager = newManager(factory) { throw UnsupportedModelException("bad file") }
            try {
                manager.loadModel(path, backend)
                fail("expected UnsupportedModelException")
            } catch (e: UnsupportedModelException) {
                assertTrue(factory.configs.isEmpty())
                assertFalse(manager.isModelLoaded)
                assertNull(manager.modelCapabilities)
            }
        }
    }

    @Test
    fun aModelThatIsNotAnLlmIsRejectedByValidation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory) { ModelCapabilities(ModelKind.EMBEDDING, true, false, false) }
        try {
            manager.loadModel(modelFile(), "CPU")
            fail("expected UnsupportedModelException")
        } catch (e: UnsupportedModelException) {
            assertTrue(factory.configs.isEmpty())
        }
    }

    @Test
    fun theGpuToCpuRetryDoesNotInspectTheFileAgain() = runTest {
        var inspections = 0
        val factory = FakeEngineFactory(failOnGpu = true)
        val manager = newManager(factory) { inspections++; FULL_LLM }
        manager.loadModel(modelFile(), "GPU")
        assertEquals(1, inspections)
        assertEquals(2, factory.configs.size)
    }

    @Test
    fun capabilitiesAreSetAfterLoadAndClearedByClose() = runTest {
        val manager = newManager(FakeEngineFactory())
        assertNull(manager.modelCapabilities)
        manager.loadModel(modelFile(), "CPU")
        assertEquals(FULL_LLM, manager.modelCapabilities)
        manager.close()
        assertNull(manager.modelCapabilities)
    }

    @Test
    fun aValidModelLoadsAfterAnUnsupportedOne() = runTest {
        val factory = FakeEngineFactory()
        var firstCall = true
        val manager = newManager(factory) { if (firstCall) { firstCall = false; throw UnsupportedModelException("bad") } else FULL_LLM }
        val path = modelFile()
        try {
            manager.loadModel(path, "CPU")
            fail("expected UnsupportedModelException")
        } catch (e: UnsupportedModelException) {
            assertFalse(manager.isModelLoaded)
        }
        manager.loadModel(path, "CPU")
        assertTrue(manager.isModelLoaded)
    }

    @Test
    fun aFailedLoadAfterASuccessfulOneLeavesNoCapabilities() = runTest {
        var failNext = false
        val manager = newManager({ config ->
            if (failNext) throw IllegalStateException("boom")
            FakeEngineFactory().invoke(config)
        })
        val path = modelFile()
        manager.loadModel(path, "CPU")
        assertEquals(FULL_LLM, manager.modelCapabilities)
        failNext = true
        try {
            manager.loadModel(File(tmp.root, "other.litertlm").also { it.writeText("x") }.absolutePath, "CPU")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertNull(manager.modelCapabilities)
        }
    }
}
