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
        val manager = AiEngineManager(factory)
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
        val manager = AiEngineManager(FakeEngineFactory())
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
        val manager = AiEngineManager(FakeEngineFactory())
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
        val manager = AiEngineManager(factory)
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
        val manager = AiEngineManager(factory)
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
        // The first conversation stays cached (it holds a different history/settings) until it is evicted or cleared
        assertFalse(factory.engine.conversations[0].closed)
        manager.clearConversationCache()
        assertTrue(factory.engine.conversations[0].closed)
        assertTrue(factory.engine.conversations[1].closed)
    }

    @Test
    fun closeReleasesEngineAndConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("a")), onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
        )

        manager.close()

        assertFalse(manager.isModelLoaded)
        assertTrue(factory.engine.closed)
        assertTrue(factory.engine.conversations[0].closed)
    }

    private fun userChat(text: String) = ChatMessage("user", JsonPrimitive(text))
    private fun assistantChat(text: String) = ChatMessage("assistant", JsonPrimitive(text))

    /** Sends [messages] as a fresh stateless request (a new conversation id, as the REST server does). */
    private suspend fun AiEngineManager.request(messages: List<ChatMessage>, reply: (String) -> Unit = {}, error: (Throwable) -> Unit = {}) =
        generateConversationResponseAsync(
            ConversationState(conversationId = "req-${System.nanoTime()}", apiToken = "t", ttlMs = 0),
            messages, onToken = {}, onComplete = { text, _ -> reply(text) }, onErrorCallback = error
        )

    @Test
    fun aRequestThatExtendsThePreviousTurnReusesItAndSendsOnlyTheNewMessage() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        // The client sends the whole history back, with the reply unchanged ("Hello" is the fake's reply)
        manager.request(listOf(userChat("one"), assistantChat("Hello"), userChat("two")))
        manager.request(listOf(userChat("one"), assistantChat("Hello"), userChat("two"), assistantChat("Hello"), userChat("three")))
        assertEquals(1, factory.engine.conversations.size)
        assertEquals(3, factory.engine.conversations[0].sent)
    }

    @Test
    fun aChangedEarlierMessageCreatesAFreshConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        manager.request(listOf(userChat("ONE (edited)"), assistantChat("Hello"), userChat("two")))
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun aChangedReplyTextCreatesAFreshConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        manager.request(listOf(userChat("one"), assistantChat("Hello (trimmed by the client)"), userChat("two")))
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun anErrorClosesTheConversationOnceAndDoesNotCacheIt() = runTest {
        val factory = FakeEngineFactory(failure = IllegalStateException("boom"))
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val errors = mutableListOf<Throwable>()
        manager.request(listOf(userChat("one")), error = { errors.add(it) })
        assertEquals(1, errors.size)
        assertTrue(factory.engine.conversations[0].closed)
        // A retry with the same messages builds a new conversation: nothing was cached
        manager.request(listOf(userChat("one")), error = { errors.add(it) })
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun anEmptyReplyIsNotCached() = runTest {
        val factory = FakeEngineFactory(chunks = emptyList())
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        manager.request(listOf(userChat("one")))
        assertEquals(2, factory.engine.conversations.size)
        assertTrue(factory.engine.conversations.all { it.closed })
    }

    @Test
    fun loadModelWithANewPathClosesTheCachedConversationsAndTheOldEngine() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val firstEngine = factory.engine
        manager.request(listOf(userChat("one")))
        manager.loadModel(File(tmp.root, "other.litertlm").also { it.writeText("x") }.absolutePath, "CPU")
        assertTrue(firstEngine.closed)
        assertTrue(firstEngine.conversations[0].closed)
    }

    @Test
    fun cancellingMidGenerationClosesTheConversationOnceAndDoesNotCacheIt() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        var completed = 0
        var errors = 0
        val job = launch(Dispatchers.Default) {
            manager.request(listOf(userChat("one")), reply = { completed++ }, error = { errors++ })
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        job.cancelAndJoin()
        assertEquals(0, completed)
        assertEquals(1, errors) // the cancel is reported once, as an error
        assertTrue(factory.engine.conversations[0].closed)
        factory.engine.hangUntilCancelled = false
        manager.request(listOf(userChat("one")))
        assertEquals(2, factory.engine.conversations.size) // the cancelled one was not cached
    }

    @Test
    fun clearConversationCacheClosesIdleConversations() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        assertFalse(factory.engine.conversations[0].closed) // cached
        manager.clearConversationCache()
        assertTrue(factory.engine.conversations[0].closed)
    }

    @Test
    fun changedTopPTopKOrSystemInstructionEachCreateAFreshConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val history = listOf(userChat("one"))
        val followUp = listOf(userChat("one"), assistantChat("Hello"), userChat("two"))
        fun fresh(topP: Double = 0.95, topK: Int = 40, system: String? = null) =
            ConversationState(conversationId = "x", apiToken = "t", ttlMs = 0, systemInstruction = system, topP = topP, topK = topK)
        suspend fun send(state: ConversationState, messages: List<ChatMessage>) =
            manager.generateConversationResponseAsync(state, messages, onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {})
        send(fresh(), history)
        send(fresh(), followUp) // same settings: reuses
        assertEquals(1, factory.engine.conversations.size)
        send(fresh(), history)
        send(fresh(topP = 0.5), followUp)
        assertEquals(3, factory.engine.conversations.size) // 1 reused above, +1 for the second `history`, +1 for topP
        send(fresh(), history)
        send(fresh(topK = 10), followUp)
        send(fresh(), history)
        send(fresh(system = "Be brief."), followUp)
        assertEquals(7, factory.engine.conversations.size)
    }

    @Test
    fun clearConversationCacheWaitsForAGenerationInProgress() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val generation = launch(Dispatchers.Default) { manager.request(listOf(userChat("one"))) }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        val clear = launch(Dispatchers.Default) { manager.clearConversationCache() }
        withContext(Dispatchers.Default) { delay(150) }
        assertFalse(clear.isCompleted) // the generation holds the inference lock
        generation.cancelAndJoin()
        clear.join()
        assertTrue(clear.isCompleted)
    }

    @Test
    fun cacheDirReachesTheEngineConfigAlsoOnTheCpuRetry() = runTest {
        val factory = FakeEngineFactory(failOnGpu = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "GPU", cacheDir = "/cache/dir")
        assertEquals(2, factory.configs.size)
        assertEquals("/cache/dir", factory.configs[0].cacheDir)
        assertEquals("/cache/dir", factory.configs[1].cacheDir)
    }

    @Test
    fun cacheDirDefaultsToNull() = runTest {
        val factory = FakeEngineFactory()
        AiEngineManager(factory).loadModel(modelFile(), "CPU")
        org.junit.Assert.assertNull(factory.configs[0].cacheDir)
    }

    private fun chatState(id: String = "c") = ConversationState(conversationId = id, apiToken = "t", ttlMs = 0)

    @Test
    fun cancellingTheCallerCancelsNativeDecodingOnceAndFreesTheLock() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        var completed = 0

        val job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState("c1"),
                messages = listOf(userChat("hi")),
                onToken = {},
                onComplete = { _, _ -> completed++ },
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
            state = chatState("c2"),
            messages = listOf(userChat("again")),
            onToken = {},
            onComplete = { text, _ -> response = text },
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
                state = chatState(), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
            )
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        factory.engine.conversations[0].close() // closes the conversation under the running request
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
                state = chatState("c1"), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
            )
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val queued = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState("c2"), messages = listOf(userChat("queued")),
                onToken = {}, onComplete = { _, _ -> }, onErrorCallback = { errors.add(it) }
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
                state = chatState("c1"), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _ -> }, onErrorCallback = { errors.add(it) }
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
            state = chatState("c2"), messages = listOf(userChat("again")),
            onToken = {}, onComplete = { text, _ -> response = text }, onErrorCallback = { fail("unexpected ${it.message}") }
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
                state = chatState("c1"), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _ -> }, onErrorCallback = {}
            )
        }
        job.join()
        assertTrue(factory.engine.conversations[0].cancelCount >= 1) // native decoding was told to stop

        factory.engine.onSendMessage = null
        factory.engine.hangUntilCancelled = false
        var response = ""
        manager.generateConversationResponseAsync(
            state = chatState("c2"), messages = listOf(userChat("again")),
            onToken = {}, onComplete = { text, _ -> response = text }, onErrorCallback = { fail("unexpected ${it.message}") }
        )
        assertEquals("Hello", response)
    }

    @Test
    fun completionCarriesTheTokenUsageFromTheEngineCounts() = runTest {
        // 2 of the 12 tokens in the KV cache were there before this generation
        val manager = AiEngineManager(FakeEngineFactory(counts = Triple(7, 3, 12)))
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
        val manager = AiEngineManager(factory)
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
        AiEngineManager(factory).loadModel(modelFile(), "GPU")
        assertEquals(listOf(true, true), factory.benchmarkFlagAtCreation)
    }
}
