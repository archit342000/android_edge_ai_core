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
        val manager = AiEngineManager(factory)
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
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "GPU")
        assertEquals(2, factory.configs.size)
        assertTrue(factory.configs[0].backend is Backend.GPU)
        assertTrue(factory.configs[1].backend is Backend.CPU)
        assertTrue(manager.isModelLoaded)
        assertEquals("CPU", manager.activeBackend)
        assertTrue(manager.fellBackFromGpu)
    }

    @Test
    fun backendNameMapsToBackendType() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        val path = modelFile()
        manager.loadModel(path, "npu")
        manager.close()
        manager.loadModel(path, "bogus")
        assertTrue(factory.configs[0].backend is Backend.NPU)
        assertTrue(factory.configs[1].backend is Backend.GPU)
    }

    @Test
    fun loadModelRethrowsCpuFailure() = runTest {
        val manager = AiEngineManager { throw IllegalStateException("boom") }
        try {
            manager.loadModel(modelFile(), "CPU")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
            assertFalse(manager.isModelLoaded)
        }
    }

    @Test
    fun generateStreamsTokensAndCompletesOnce() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")

        val tokens = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val state = state()
        manager.generateConversationResponseAsync(
            state, listOf(userMessage("hi")),
            onToken = { tokens.add(it) },
            onComplete = { completed.add(it) },
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
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")

        var completes = 0
        val errors = mutableListOf<Throwable>()
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("hi")),
            onToken = {},
            onComplete = { completes++ },
            onErrorCallback = { errors.add(it) }
        )

        assertEquals(0, completes)
        assertEquals(1, errors.size)
        assertEquals("engine failed", errors[0].message)
    }

    @Test
    fun generateWithoutModelThrowsIllegalState() = runTest {
        val manager = AiEngineManager(FakeEngineFactory())
        try {
            manager.generateConversationResponseAsync(
                state(), listOf(userMessage("hi")),
                onToken = {}, onComplete = {}, onErrorCallback = {}
            )
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Model not loaded", e.message)
        }
    }

    @Test
    fun emptyMessagesReportAnError() = runTest {
        val manager = AiEngineManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "CPU")
        val errors = mutableListOf<Throwable>()
        manager.generateConversationResponseAsync(
            state(), emptyList(),
            onToken = {}, onComplete = {}, onErrorCallback = { errors.add(it) }
        )
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalArgumentException)
    }

    @Test
    fun sameConversationWithOneNewMessageReusesConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val state = state()

        repeat(2) { i ->
            manager.generateConversationResponseAsync(
                state, listOf(userMessage("m$i")),
                onToken = {}, onComplete = {}, onErrorCallback = {}
            )
        }

        assertEquals(1, factory.engine.conversations.size)
        assertEquals(2, factory.engine.conversations[0].sent)
    }

    @Test
    fun changedSamplingParamsRebuildConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val state = state()

        manager.generateConversationResponseAsync(
            state, listOf(userMessage("a")), onToken = {}, onComplete = {}, onErrorCallback = {}
        )
        state.temperature = 0.1
        manager.generateConversationResponseAsync(
            state, listOf(userMessage("b")), onToken = {}, onComplete = {}, onErrorCallback = {}
        )

        assertEquals(2, factory.engine.conversations.size)
        assertTrue(factory.engine.conversations[0].closed)
    }

    @Test
    fun closeReleasesEngineAndConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("a")), onToken = {}, onComplete = {}, onErrorCallback = {}
        )

        manager.close()

        assertFalse(manager.isModelLoaded)
        assertTrue(factory.engine.closed)
        assertTrue(factory.engine.conversations[0].closed)
    }

    @Test
    fun aGpuThatLoadsIsReportedAsGpuWithoutFallback() = runTest {
        val manager = AiEngineManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "GPU")
        assertEquals("GPU", manager.activeBackend)
        assertFalse(manager.fellBackFromGpu)
        manager.close()
        assertNull(manager.activeBackend)
        assertFalse(manager.fellBackFromGpu)
    }

    @Test
    fun verifyReadyReturnsNullWhenTheModelAnswers() = runTest {
        val manager = AiEngineManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "CPU")
        val attempts = mutableListOf<Throwable?>()
        val result = manager.verifyReady(attempts = 3, retryDelayMs = 0) { _, error -> attempts.add(error) }
        assertNull(result)
        assertEquals(listOf<Throwable?>(null), attempts) // stops at the first success
    }

    @Test
    fun verifyReadyReturnsTheLastErrorAfterAllAttempts() = runTest {
        val manager = AiEngineManager(FakeEngineFactory(failure = IllegalStateException("no OpenCL")))
        manager.loadModel(modelFile(), "CPU")
        var calls = 0
        val result = manager.verifyReady(attempts = 2, retryDelayMs = 0) { _, _ -> calls++ }
        assertEquals("no OpenCL", result?.message)
        assertEquals(2, calls)
    }

    @Test
    fun fallBackToCpuReloadsOnCpuAndRecordsIt() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        val path = modelFile()
        manager.loadModel(path, "GPU")
        manager.fallBackToCpu(path)
        assertEquals("CPU", manager.activeBackend)
        assertTrue(manager.fellBackFromGpu)
        assertTrue(factory.configs.last().backend is Backend.CPU)
    }

    @Test
    fun anUnknownBackendNameRunsAndReportsGpu() = runTest {
        val manager = AiEngineManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "bogus")
        assertEquals("GPU", manager.activeBackend)
    }

    @Test
    fun verifyReadyTreatsAnEmptyReplyAsAFailureAndClosesThePingConversation() = runTest {
        val factory = FakeEngineFactory(chunks = emptyList())
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val result = manager.verifyReady(attempts = 1, retryDelayMs = 0)
        assertEquals("Empty response", result?.message)
        assertTrue(factory.engine.conversations.all { it.closed })
    }

    @Test
    fun fallBackToCpuIsRefusedForANonGpuEngine() = runTest {
        val manager = AiEngineManager(FakeEngineFactory())
        val path = modelFile()
        manager.loadModel(path, "CPU")
        try {
            manager.fallBackToCpu(path)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(manager.fellBackFromGpu)
            assertEquals("CPU", manager.activeBackend)
        }
    }

    @Test
    fun aFailingCpuReloadLeavesNoBackendAndNoFallbackFlag() = runTest {
        var failCpu = false
        val manager = AiEngineManager({ config ->
            if (failCpu) throw IllegalStateException("cpu boom")
            FakeEngineFactory().invoke(config)
        })
        val path = modelFile()
        manager.loadModel(path, "GPU")
        failCpu = true
        try {
            manager.fallBackToCpu(path)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertNull(manager.activeBackend)
            assertFalse(manager.fellBackFromGpu)
            assertFalse(manager.isModelLoaded)
        }
    }

    @Test
    fun anUnknownBackendWhoseGpuInitFailsFallsBackToCpu() = runTest {
        val factory = FakeEngineFactory(failOnGpu = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "bogus")
        assertEquals("CPU", manager.activeBackend)
        assertTrue(manager.fellBackFromGpu)
    }
}
