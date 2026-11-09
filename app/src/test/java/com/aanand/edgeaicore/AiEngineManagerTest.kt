package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Backend
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun cancellingTheCallerCancelsNativeDecodingOnceAndFreesTheLock() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        var completed = 0

        val job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = state("c1"),
                messages = listOf(userMessage("hi")),
                onToken = {},
                onComplete = { completed++ },
                onErrorCallback = { errors.add(it) }
            )
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        job.cancelAndJoin()

        val first = factory.engine.conversations[0]
        assertEquals(1, first.cancelCount)
        assertTrue(first.closed) // the partial KV state is dropped
        assertEquals(0, completed)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is CancellationException)

        // The lock is free: the next request runs and completes.
        factory.engine.hangUntilCancelled = false
        var response = ""
        manager.generateConversationResponseAsync(
            state = state("c2"),
            messages = listOf(userMessage("again")),
            onToken = {},
            onComplete = { response = it },
            onErrorCallback = { fail("unexpected error ${it.message}") }
        )
        assertEquals("Hello", response)
    }

    @Test
    fun cancelSkipsCancelProcessOnAClosedConversation() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.cancelWaitMs = 50
        manager.loadModel(modelFile(), "CPU")
        val job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = state(), messages = listOf(userMessage("hi")),
                onToken = {}, onComplete = {}, onErrorCallback = {}
            )
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        manager.closeAllConversations() // closes the conversation under the running request
        job.cancelAndJoin()
        assertEquals(0, factory.engine.conversations[0].cancelCount)
    }

    @Test
    fun cancellingAQueuedRequestReportsOnceAndNeverReachesTheEngine() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val first = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = state("c1"), messages = listOf(userMessage("hi")),
                onToken = {}, onComplete = {}, onErrorCallback = {}
            )
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val queued = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = state("c2"), messages = listOf(userMessage("queued")),
                onToken = {}, onComplete = {}, onErrorCallback = { errors.add(it) }
            )
        }
        withContext(Dispatchers.Default) { delay(100) } // let the second request reach the lock
        queued.cancelAndJoin()

        assertEquals(1, errors.size)
        assertTrue(errors[0] is CancellationException)
        assertEquals(1, factory.engine.conversations.size) // the queued request never created a conversation
        first.cancelAndJoin()
    }

    @Test
    fun cancelDuringConversationCreationNeverStartsDecodingAndFreesTheLock() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        lateinit var job: kotlinx.coroutines.Job
        factory.engine.onCreateConversation = { job.cancel() } // the client goes away while the history is rebuilt
        job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = state("c1"), messages = listOf(userMessage("hi")),
                onToken = {}, onComplete = {}, onErrorCallback = { errors.add(it) }
            )
        }
        job.join()

        assertEquals(0, factory.engine.conversations[0].sent) // decoding never started
        assertEquals(1, errors.size)

        // The lock is free: the next request completes.
        factory.engine.onCreateConversation = null
        factory.engine.hangUntilCancelled = false
        var response = ""
        manager.generateConversationResponseAsync(
            state = state("c2"), messages = listOf(userMessage("again")),
            onToken = {}, onComplete = { response = it }, onErrorCallback = { fail("unexpected ${it.message}") }
        )
        assertEquals("Hello", response)
    }

    @Test
    fun cancelRightAfterTheSendStopsTheDecodingThatJustStarted() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        lateinit var job: kotlinx.coroutines.Job
        factory.engine.onSendMessage = { job.cancel() } // the client goes away just as decoding starts
        job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = state("c1"), messages = listOf(userMessage("hi")),
                onToken = {}, onComplete = {}, onErrorCallback = {}
            )
        }
        job.join()
        assertTrue(factory.engine.conversations[0].cancelCount >= 1) // native decoding was told to stop

        factory.engine.onSendMessage = null
        factory.engine.hangUntilCancelled = false
        var response = ""
        manager.generateConversationResponseAsync(
            state = state("c2"), messages = listOf(userMessage("again")),
            onToken = {}, onComplete = { response = it }, onErrorCallback = { fail("unexpected ${it.message}") }
        )
        assertEquals("Hello", response)
    }
}
