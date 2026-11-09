package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.gson.Gson
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

    private val withDrafter = FULL_LLM.copy(speculativeDecoding = true)

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
        assertEquals("CPU", manager.activeBackend)
        assertTrue(manager.fellBackFromGpu)
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
            onComplete = { text, _, _ -> completed.add(text) },
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
            onComplete = { _, _, _ -> completes++ },
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
                onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
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
            onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { errors.add(it) }
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
                onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
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
            state, listOf(userMessage("a")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
        )
        state.temperature = 0.1
        manager.generateConversationResponseAsync(
            state, listOf(userMessage("b")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("a")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
        )

        manager.close()

        assertFalse(manager.isModelLoaded)
        assertTrue(factory.engine.closed)
        assertTrue(factory.engine.conversations[0].closed)
    }

    private fun userChat(text: String) = ChatMessage("user", JsonPrimitive(text))
    private fun assistantChat(text: String) = ChatMessage("assistant", JsonPrimitive(text))

    /** Sends [messages] as a fresh stateless request (a new conversation id, as the REST server does). */
    private suspend fun AiEngineManager.request(
        messages: List<ChatMessage>,
        reply: (String) -> Unit = {},
        error: (Throwable) -> Unit = {},
        chatOptions: ChatOptions = ChatOptions()
    ) = generateConversationResponseAsync(
        ConversationState(conversationId = "req-${System.nanoTime()}", apiToken = "t", ttlMs = 0),
        messages, onToken = {}, onComplete = { text, _, _ -> reply(text) }, onErrorCallback = error, chatOptions = chatOptions
    )

    @Test
    fun aRequestThatExtendsThePreviousTurnReusesItAndSendsOnlyTheNewMessage() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        manager.request(listOf(userChat("ONE (edited)"), assistantChat("Hello"), userChat("two")))
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun aChangedReplyTextCreatesAFreshConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        manager.request(listOf(userChat("one"), assistantChat("Hello (trimmed by the client)"), userChat("two")))
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun anErrorClosesTheConversationOnceAndDoesNotCacheIt() = runTest {
        val factory = FakeEngineFactory(failure = IllegalStateException("boom"))
        val manager = newManager(factory)
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        manager.request(listOf(userChat("one")))
        assertEquals(2, factory.engine.conversations.size)
        assertTrue(factory.engine.conversations.all { it.closed })
    }

    @Test
    fun loadModelWithANewPathClosesTheCachedConversationsAndTheOldEngine() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
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
        val manager = newManager(factory)
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.request(listOf(userChat("one")))
        assertFalse(factory.engine.conversations[0].closed) // cached
        manager.clearConversationCache()
        assertTrue(factory.engine.conversations[0].closed)
    }

    @Test
    fun changedTopPTopKOrSystemInstructionEachCreateAFreshConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val history = listOf(userChat("one"))
        val followUp = listOf(userChat("one"), assistantChat("Hello"), userChat("two"))
        fun fresh(topP: Double = 0.95, topK: Int = 40, system: String? = null) =
            ConversationState(conversationId = "x", apiToken = "t", ttlMs = 0, systemInstruction = system, topP = topP, topK = topK)
        suspend fun send(state: ConversationState, messages: List<ChatMessage>) =
            manager.generateConversationResponseAsync(state, messages, onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {})
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
        val manager = newManager(factory)
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "GPU", cacheDir = "/cache/dir")
        assertEquals(2, factory.configs.size)
        assertEquals("/cache/dir", factory.configs[0].cacheDir)
        assertEquals("/cache/dir", factory.configs[1].cacheDir)
    }

    @Test
    fun cacheDirDefaultsToNull() = runTest {
        val factory = FakeEngineFactory()
        newManager(factory).loadModel(modelFile(), "CPU")
        org.junit.Assert.assertNull(factory.configs[0].cacheDir)
    }

    private fun chatState(id: String = "c") = ConversationState(conversationId = id, apiToken = "t", ttlMs = 0)

    @Test
    fun cancellingTheCallerCancelsNativeDecodingOnceAndFreesTheLock() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        var completed = 0

        val job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState("c1"),
                messages = listOf(userChat("hi")),
                onToken = {},
                onComplete = { _, _, _ -> completed++ },
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
            onComplete = { text, _, _ -> response = text },
            onErrorCallback = { fail("unexpected error ${it.message}") }
        )
        assertEquals("Hello", response)
    }

    @Test
    fun cancelSkipsCancelProcessOnAClosedConversation() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = newManager(factory)
        manager.cancelWaitMs = 50
        manager.loadModel(modelFile(), "CPU")
        val job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState(), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val first = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState("c1"), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
            )
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val queued = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState("c2"), messages = listOf(userChat("queued")),
                onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { errors.add(it) }
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        lateinit var job: kotlinx.coroutines.Job
        factory.engine.onCreateConversation = { job.cancel() } // the client goes away while the history is rebuilt
        job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState("c1"), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { errors.add(it) }
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
            onToken = {}, onComplete = { text, _, _ -> response = text }, onErrorCallback = { fail("unexpected ${it.message}") }
        )
        assertEquals("Hello", response)
    }

    @Test
    fun cancelRightAfterTheSendStopsTheDecodingThatJustStarted() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        lateinit var job: kotlinx.coroutines.Job
        factory.engine.onSendMessage = { job.cancel() } // the client goes away just as decoding starts
        job = launch(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                state = chatState("c1"), messages = listOf(userChat("hi")),
                onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
            )
        }
        job.join()
        assertTrue(factory.engine.conversations[0].cancelCount >= 1) // native decoding was told to stop

        factory.engine.onSendMessage = null
        factory.engine.hangUntilCancelled = false
        var response = ""
        manager.generateConversationResponseAsync(
            state = chatState("c2"), messages = listOf(userChat("again")),
            onToken = {}, onComplete = { text, _, _ -> response = text }, onErrorCallback = { fail("unexpected ${it.message}") }
        )
        assertEquals("Hello", response)
    }

    @Test
    fun completionCarriesTheTokenUsageFromTheEngineCounts() = runTest {
        // 2 of the 12 tokens in the KV cache were there before this generation
        val manager = newManager(FakeEngineFactory(counts = Triple(7, 3, 12)))
        manager.loadModel(modelFile(), "CPU")
        var usage: TokenUsage? = null
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("hi")), onToken = {}, onComplete = { _, u, _ -> usage = u },
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
            s, listOf(userMessage("hi")), onToken = {}, onComplete = { _, _, _ -> completes++ }, onErrorCallback = { errors.add(it) }
        )
        assertEquals(0, completes)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is UsageUnavailableException)
        assertEquals(1, s.history.size) // only the user message; no assistant reply was appended
        assertTrue(factory.engine.conversations[0].closed) // the engine holds a reply the history lacks

        // The same conversation id is rebuilt, not reused.
        manager.generateConversationResponseAsync(
            s, listOf(userMessage("again")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {}
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

    @Test
    fun withoutASystemInstructionTheEngineGetsNone() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("hi")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { fail(it.message) }
        )
        assertNull(factory.engine.conversationConfigs[0].systemInstructionText())
    }

    @Test
    fun aSystemInstructionReachesTheEngineConfig() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val s = state().copy(systemInstruction = "Always answer in French.")
        manager.generateConversationResponseAsync(
            s, listOf(userMessage("hi")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { fail(it.message) }
        )
        assertEquals("Always answer in French.", factory.engine.conversationConfigs[0].systemInstructionText())
    }

    @Test
    fun aChangedSystemInstructionRebuildsTheConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val first = state()
        manager.generateConversationResponseAsync(
            first, listOf(userMessage("hi")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { fail(it.message) }
        )
        // Same conversation id, one new message, same sampling: reused, unless the system instruction changed.
        manager.generateConversationResponseAsync(
            first, listOf(userMessage("again")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { fail(it.message) }
        )
        assertEquals(1, factory.engine.conversations.size)
        val changed = first.copy(systemInstruction = "New rules.")
        manager.generateConversationResponseAsync(
            changed, listOf(userMessage("third")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { fail(it.message) }
        )
        assertEquals(2, factory.engine.conversations.size)
        assertEquals("New rules.", factory.engine.conversationConfigs[1].systemInstructionText())
    }

    @Test
    fun maxNumTokensReachesTheEngineConfigAlsoOnTheCpuRetry() = runTest {
        val factory = FakeEngineFactory(failOnGpu = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "GPU", maxNumTokens = 1024)
        assertEquals(2, factory.configs.size)
        assertEquals(1024, factory.configs[0].maxNumTokens)
        assertEquals(1024, factory.configs[1].maxNumTokens)
    }

    @Test
    fun maxNumTokensDefaultsToNullAndIsNotSetUnlessGiven() = runTest {
        val factory = FakeEngineFactory()
        newManager(factory).loadModel(modelFile(), "CPU")
        assertNull(factory.configs[0].maxNumTokens)
    }

    @Test
    fun aChangedContextSizeReloadsTheSameModelButTheSameOneDoesNot() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        val path = modelFile()
        manager.loadModel(path, "CPU", maxNumTokens = 1024)
        manager.loadModel(path, "CPU", maxNumTokens = 1024)
        assertEquals(1, factory.configs.size)
        manager.loadModel(path, "CPU", maxNumTokens = 2048)
        assertEquals(2, factory.configs.size)
        assertEquals(2048, factory.configs[1].maxNumTokens)
    }

    @Test
    fun perRequestOptionsReachTheConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val options = GenerationOptions(maxOutputTokens = 5, presencePenalty = 0.5f, frequencyPenalty = -1f)
        manager.generateConversationResponseAsync(
            state(), listOf(userMessage("hi")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { fail(it.message) }, options = options
        )
        assertEquals(options, factory.engine.conversations[0].lastOptions)
    }

    @Test
    fun aChangedSeedRebuildsTheConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val first = state()
        manager.generateConversationResponseAsync(first, listOf(userMessage("a")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {})
        manager.generateConversationResponseAsync(first, listOf(userMessage("b")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {})
        assertEquals(1, factory.engine.conversations.size) // same id, same settings: reused
        first.seed = 7
        manager.generateConversationResponseAsync(first, listOf(userMessage("c")), onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = {})
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun noOptionsMeansEngineDefaults() {
        val options = GenerationOptions()
        assertNull(options.repetitionPenaltyConfig())
        assertEquals(0.5f, GenerationOptions(presencePenalty = 0.5f).repetitionPenaltyConfig()!!.presencePenalty)
    }

    @Test
    fun aGpuThatLoadsIsReportedAsGpuWithoutFallback() = runTest {
        val manager = newManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "GPU")
        assertEquals("GPU", manager.activeBackend)
        assertFalse(manager.fellBackFromGpu)
        manager.close()
        assertNull(manager.activeBackend)
        assertFalse(manager.fellBackFromGpu)
    }

    @Test
    fun verifyReadyReturnsNullWhenTheModelAnswers() = runTest {
        val manager = newManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "CPU")
        val attempts = mutableListOf<Throwable?>()
        val result = manager.verifyReady(attempts = 3, retryDelayMs = 0) { _, error -> attempts.add(error) }
        assertNull(result)
        assertEquals(listOf<Throwable?>(null), attempts) // stops at the first success
    }

    @Test
    fun verifyReadyReturnsTheLastErrorAfterAllAttempts() = runTest {
        val manager = newManager(FakeEngineFactory(failure = IllegalStateException("no OpenCL")))
        manager.loadModel(modelFile(), "CPU")
        var calls = 0
        val result = manager.verifyReady(attempts = 2, retryDelayMs = 0) { _, _ -> calls++ }
        assertEquals("no OpenCL", result?.message)
        assertEquals(2, calls)
    }

    @Test
    fun fallBackToCpuReloadsOnCpuAndRecordsIt() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        val path = modelFile()
        manager.loadModel(path, "GPU")
        manager.fallBackToCpu(path)
        assertEquals("CPU", manager.activeBackend)
        assertTrue(manager.fellBackFromGpu)
        assertTrue(factory.configs.last().backend is Backend.CPU)
    }

    @Test
    fun anUnknownBackendNameRunsAndReportsGpu() = runTest {
        val manager = newManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "bogus")
        assertEquals("GPU", manager.activeBackend)
    }

    @Test
    fun verifyReadyTreatsAnEmptyReplyAsAFailureAndClosesThePingConversation() = runTest {
        val factory = FakeEngineFactory(chunks = emptyList())
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val result = manager.verifyReady(attempts = 1, retryDelayMs = 0)
        assertEquals("Empty response", result?.message)
        assertTrue(factory.engine.conversations.all { it.closed })
    }

    @Test
    fun fallBackToCpuIsRefusedForANonGpuEngine() = runTest {
        val manager = newManager(FakeEngineFactory())
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
        val manager = newManager({ config ->
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
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "bogus")
        assertEquals("CPU", manager.activeBackend)
        assertTrue(manager.fellBackFromGpu)
    }

    private val weatherTool = ToolDto("function", ToolFunctionDto("get_weather", "d", null))

    @Test
    fun toolsReachTheConversationConfigWithManualToolCalling() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val options = OpenAIToolMapper.prepare(ChatCompletionRequest(messages = listOf(userChat("hi")), tools = listOf(weatherTool)))
        manager.request(listOf(userChat("hi")), chatOptions = options)
        val config = factory.engine.conversationConfigs[0]
        assertEquals(1, config.tools.size)
        assertEquals(false, config.automaticToolCalling)
    }

    @Test
    fun toolCallsAndReasoningReachTheCallbacks() = runTest {
        val factory = FakeEngineFactory(
            chunks = emptyList(),
            toolCalls = listOf(com.google.ai.edge.litertlm.ToolCall("get_weather", mapOf("city" to "Paris"))),
            thinking = "let me think"
        )
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val calls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()
        val reasoning = StringBuilder()
        manager.generateConversationResponseAsync(
            ConversationState(conversationId = "x", apiToken = "t", ttlMs = 0), listOf(userChat("hi")),
            onToken = {}, onComplete = { _, _, _ -> }, onErrorCallback = { fail(it.message) },
            onReasoning = { reasoning.append(it) }, onToolCalls = { calls.addAll(it) }
        )
        assertEquals("get_weather", calls.single().name)
        assertEquals("let me think", reasoning.toString())
    }

    @Test
    fun perRequestResponseFormatAndThinkingReachTheSend() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val options = OpenAIToolMapper.prepare(
            ChatCompletionRequest(messages = listOf(userChat("hi")), response_format = ResponseFormatDto("json_object"), reasoning_effort = "high")
        )
        manager.request(listOf(userChat("hi")), chatOptions = options)
        val conversation = factory.engine.conversations[0]
        assertEquals(true, conversation.lastResponseFormat != null)
        assertEquals(true, conversation.lastThinking!!.enableThinking)
        assertEquals(true, factory.engine.conversationConfigs[0].enableResponseFormat)
    }

    @Test
    fun aToolResultInTheHistoryBecomesAToolMessage() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val assistant = ChatMessage("assistant", null, listOf(ToolCallDto(id = "call_1", function = FunctionCallDto("get_weather", "{\"city\":\"Paris\"}"))))
        val toolResult = ChatMessage("tool", JsonPrimitive("{\"temp\":20}"), tool_call_id = "call_1")
        manager.request(listOf(userChat("weather in Paris?"), assistant, toolResult))
        val config = factory.engine.conversationConfigs[0]
        val roles = config.initialMessages.map { it.role }
        assertEquals(listOf(com.google.ai.edge.litertlm.Role.USER, com.google.ai.edge.litertlm.Role.MODEL), roles)
        assertEquals("get_weather", config.initialMessages[1].toolCalls.single().name)
        assertEquals(com.google.ai.edge.litertlm.Role.TOOL, factory.engine.conversations[0].lastMessage!!.role)
    }

    @Test
    fun differentToolsDoNotReuseACachedConversation() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val withTools = OpenAIToolMapper.prepare(ChatCompletionRequest(messages = listOf(userChat("hi")), tools = listOf(weatherTool)))
        manager.request(listOf(userChat("one")), chatOptions = withTools)
        manager.request(listOf(userChat("one"), assistantChat("Hello"), userChat("two")))
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun anAssistantTurnWithNullContentAndToolCallsMapsWithoutANullText() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val assistant = Gson().fromJson(
            """{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","function":{"name":"f","arguments":"{}"}}]}""",
            ChatMessage::class.java
        )
        val toolResult = ChatMessage("tool", JsonPrimitive("done"), tool_call_id = "call_1")
        manager.request(listOf(userChat("q"), assistant, toolResult))
        val model = factory.engine.conversationConfigs[0].initialMessages[1]
        assertEquals(0, model.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().size)
        assertEquals("f", model.toolCalls.single().name)
    }

    @Test
    fun aSupportingModelGetsTheConfiguredSpeculativeMode() = runTest {
        val path = modelFile()
        for ((option, mode) in listOf(
            SpeculativeDecodingOption.ON to SpeculativeDecodingMode.ON,
            SpeculativeDecodingOption.ADAPTIVE to SpeculativeDecodingMode.ADAPTIVE
        )) {
            val factory = FakeEngineFactory()
            val manager = newManager(factory) { withDrafter }
            manager.loadModel(path, "CPU", speculativeDecoding = option)
            assertEquals(mode, factory.configs[0].speculativeDecodingConfig?.mode)
        }
    }

    @Test
    fun offAndUnsupportedModelsGetNoSpeculativeConfig() = runTest {
        val path = modelFile()
        val off = FakeEngineFactory()
        newManager(off) { withDrafter }.loadModel(path, "CPU", speculativeDecoding = SpeculativeDecodingOption.OFF)
        assertNull(off.configs[0].speculativeDecodingConfig)
        val unsupported = FakeEngineFactory()
        newManager(unsupported) { FULL_LLM }.loadModel(path, "CPU", speculativeDecoding = SpeculativeDecodingOption.ADAPTIVE)
        assertNull(unsupported.configs[0].speculativeDecodingConfig)
    }

    @Test
    fun theSpeculativeModeSurvivesTheCpuRetry() = runTest {
        val factory = FakeEngineFactory(failOnGpu = true)
        val manager = newManager(factory) { withDrafter }
        manager.loadModel(modelFile(), "GPU", speculativeDecoding = SpeculativeDecodingOption.ADAPTIVE)
        assertEquals(2, factory.configs.size)
        assertEquals(SpeculativeDecodingMode.ADAPTIVE, factory.configs[1].speculativeDecodingConfig?.mode)
    }

    @Test
    fun theDefaultOptionIsAdaptive() = runTest {
        val factory = FakeEngineFactory()
        newManager(factory) { withDrafter }.loadModel(modelFile(), "CPU")
        assertEquals(SpeculativeDecodingMode.ADAPTIVE, factory.configs[0].speculativeDecodingConfig?.mode)
    }

    @Test
    fun theActiveSpeculativeModeIsReportedAndClearedByClose() = runTest {
        val manager = newManager(FakeEngineFactory()) { withDrafter }
        manager.loadModel(modelFile(), "CPU", speculativeDecoding = SpeculativeDecodingOption.ON)
        assertEquals(SpeculativeDecodingMode.ON, manager.activeSpeculativeMode)
        manager.close()
        assertNull(manager.activeSpeculativeMode)
    }

    @Test
    fun aChangedSpeculativeOptionReloadsTheSameModel() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory) { withDrafter }
        val path = modelFile()
        manager.loadModel(path, "CPU", speculativeDecoding = SpeculativeDecodingOption.ADAPTIVE)
        manager.loadModel(path, "CPU", speculativeDecoding = SpeculativeDecodingOption.ADAPTIVE)
        assertEquals(1, factory.configs.size)
        manager.loadModel(path, "CPU", speculativeDecoding = SpeculativeDecodingOption.OFF)
        assertEquals(2, factory.configs.size)
        assertNull(manager.activeSpeculativeMode)
    }

    private val cpuOnlyVision = FULL_LLM.copy(visionBackends = listOf(com.google.ai.edge.litertlm.BackendType.CPU))
    private val textOnly = FULL_LLM.copy(supportsVision = false, supportsAudio = false, visionBackends = null, audioBackends = null)

    @Test
    fun aTextOnlyModelGetsNoEncoders() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory) { textOnly }
        manager.loadModel(modelFile(), "GPU")
        assertNull(factory.configs[0].visionBackend)
        assertNull(factory.configs[0].audioBackend)
        assertEquals(false, manager.activeEncoders.supportsImages)
        assertEquals(false, manager.activeEncoders.supportsAudio)
    }

    @Test
    fun aMultimodalModelGetsEncodersOnTheChosenBackends() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "GPU", visionOption = EncoderBackendOption.CPU, audioOption = EncoderBackendOption.AUTO)
        assertTrue(factory.configs[0].visionBackend is Backend.CPU)
        assertTrue(factory.configs[0].audioBackend is Backend.GPU) // AUTO follows the main backend
        assertEquals(true, manager.activeEncoders.supportsImages)
    }

    @Test
    fun anUnsupportedOverrideFallsBackAndIsReported() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory) { cpuOnlyVision }
        manager.loadModel(modelFile(), "CPU", visionOption = EncoderBackendOption.GPU)
        assertTrue(factory.configs[0].visionBackend is Backend.CPU)
        assertEquals(true, manager.activeEncoders.visionOverrideIgnored)
    }

    @Test
    fun theEncoderOptionsSurviveTheMainBackendCpuRetry() = runTest {
        val factory = FakeEngineFactory(failOnGpu = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "GPU", visionOption = EncoderBackendOption.CPU, audioOption = EncoderBackendOption.CPU)
        assertEquals(2, factory.configs.size)
        assertTrue(factory.configs[1].visionBackend is Backend.CPU)
        assertTrue(factory.configs[1].audioBackend is Backend.CPU)
    }

    @Test
    fun closeClearsTheActiveEncoders() = runTest {
        val manager = newManager(FakeEngineFactory())
        manager.loadModel(modelFile(), "CPU")
        assertEquals(true, manager.activeEncoders.supportsImages)
        manager.close()
        assertEquals(false, manager.activeEncoders.supportsImages)
    }

    @Test
    fun aContextSizeAboveTheModelMaximumIsClamped() = runTest {
        val path = modelFile()
        val limited = FULL_LLM.copy(maxContextTokens = 4096)
        val over = FakeEngineFactory()
        val m1 = newManager(over) { limited }
        m1.loadModel(path, "CPU", maxNumTokens = 10_000)
        assertEquals(4096, over.configs[0].maxNumTokens)
        assertEquals(4096, m1.effectiveMaxNumTokens)
        val under = FakeEngineFactory()
        newManager(under) { limited }.loadModel(path, "CPU", maxNumTokens = 2048)
        assertEquals(2048, under.configs[0].maxNumTokens)
        val unknown = FakeEngineFactory()
        newManager(unknown) { FULL_LLM }.loadModel(path, "CPU", maxNumTokens = 10_000)
        assertEquals(10_000, unknown.configs[0].maxNumTokens)
        val dynamic = FakeEngineFactory()
        newManager(dynamic) { limited.copy(dynamicContext = true) }.loadModel(path, "CPU", maxNumTokens = 10_000)
        assertEquals(10_000, dynamic.configs[0].maxNumTokens)
        val none = FakeEngineFactory()
        val m5 = newManager(none) { limited }
        m5.loadModel(path, "CPU")
        assertNull(none.configs[0].maxNumTokens)
        assertNull(m5.effectiveMaxNumTokens)
    }

    @Test
    fun aRejectedFileKeepsTheRunningModelLoaded() = runTest {
        val factory = FakeEngineFactory()
        val manager = newManager(factory) { path ->
            if (path.endsWith("bad.litertlm")) throw UnsupportedModelException("not a model") else FULL_LLM
        }
        val good = modelFile()
        manager.loadModel(good, "CPU")
        val bad = File(tmp.root, "bad.litertlm").also { it.writeText("x") }.absolutePath
        try {
            manager.loadModel(bad, "CPU")
            fail("expected UnsupportedModelException")
        } catch (e: UnsupportedModelException) {
            assertTrue(manager.isModelLoaded)
            assertFalse(factory.engine.closed)
            assertEquals(FULL_LLM, manager.modelCapabilities)
        }
        val none = newManager(FakeEngineFactory()) { throw UnsupportedModelException("not a model") }
        try { none.loadModel(bad, "CPU"); fail("expected UnsupportedModelException") } catch (e: UnsupportedModelException) { assertFalse(none.isModelLoaded) }
    }

    @Test
    fun closeWhenIdleWaitsForARunningGenerationThenClosesAfterTheTimeout() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val generation = launch(Dispatchers.Default) { manager.request(listOf(userChat("one"))) }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        // The generation never ends: the engine is closed anyway after the timeout
        withContext(Dispatchers.Default) { manager.closeWhenIdle(150) }
        assertTrue(factory.engine.closed)
        assertFalse(manager.isModelLoaded)
        generation.cancelAndJoin()
        // An idle engine closes at once
        val idle = FakeEngineFactory()
        val m2 = newManager(idle)
        m2.loadModel(modelFile2(), "CPU")
        m2.closeWhenIdle(5_000)
        assertTrue(idle.engine.closed)
    }

    private fun modelFile2() = tmp.newFile("second-${System.nanoTime()}.litertlm").absolutePath

    private suspend fun AiEngineManager.stopRequest(stop: List<String>, messages: List<ChatMessage> = listOf(userChat("one")), tokens: MutableList<String> = mutableListOf(), done: MutableList<Pair<String, Boolean>> = mutableListOf()) =
        generateConversationResponseAsync(
            ConversationState(conversationId = "stop-${System.nanoTime()}", apiToken = "t", ttlMs = 0), messages,
            onToken = { tokens.add(it) }, onComplete = { text, _, stopped -> done.add(text to stopped) }, onErrorCallback = { fail("unexpected ${it.message}") },
            chatOptions = ChatOptions(stopSequences = stop)
        )

    @Test
    fun aStopSequenceEndsTheReplyBeforeItAndDoesNotCacheTheConversation() = runTest {
        for (errorEnding in listOf(false, true)) {
            val factory = FakeEngineFactory(chunks = listOf("Hel", "lo", " wor", "ld"))
            val manager = newManager(factory)
            manager.loadModel(modelFile2(), "CPU")
            factory.engine.endWithErrorOnCancel = errorEnding
            val tokens = mutableListOf<String>(); val done = mutableListOf<Pair<String, Boolean>>()
            manager.stopRequest(listOf("lo w"), tokens = tokens, done = done)
            assertEquals(errorEnding.toString(), 1, done.size)
            assertEquals("Hel" to true, done.single())
            assertEquals("Hel", tokens.joinToString(""))
            assertEquals(1, factory.engine.conversations[0].cancelCount)
            assertTrue(factory.engine.conversations[0].closed) // not cached
            // the next request runs and rebuilds (no cache hit)
            manager.stopRequest(emptyList(), messages = listOf(userChat("one"), assistantChat("Hel"), userChat("two")))
            assertEquals(2, factory.engine.conversations.size)
        }
    }

    @Test
    fun aReplyEndingInAHeldBackPrefixKeepsItsTail() = runTest {
        val factory = FakeEngineFactory(chunks = listOf("ends with ST"))
        val manager = newManager(factory)
        manager.loadModel(modelFile2(), "CPU")
        val tokens = mutableListOf<String>(); val done = mutableListOf<Pair<String, Boolean>>()
        manager.stopRequest(listOf("STOP"), tokens = tokens, done = done)
        assertEquals("ends with ST", tokens.joinToString(""))
        assertEquals("ends with ST" to false, done.single())
        assertEquals(0, factory.engine.conversations[0].cancelCount)
    }

    @Test
    fun aRequestThatWaitsTooLongForTheEngineIsRefusedAsBusyAndTheLockStaysWithTheFirst() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile2(), "CPU")
        val first = launch(Dispatchers.Default) { manager.request(listOf(userChat("one"))) }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        withContext(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                ConversationState(conversationId = "q", apiToken = "t", ttlMs = 0), listOf(userChat("two")),
                onToken = {}, onComplete = { _, _, _ -> fail("must not complete") }, onErrorCallback = { errors.add(it) },
                queueWaitMs = 100
            )
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0] is EngineBusyException)
        assertEquals(1, factory.engine.conversations.size) // the refused request never reached the engine
        first.cancelAndJoin()
        // the lock was not released twice: the next request completes
        factory.engine.hangUntilCancelled = false
        var done = false
        manager.generateConversationResponseAsync(
            ConversationState(conversationId = "n", apiToken = "t", ttlMs = 0), listOf(userChat("three")),
            onToken = {}, onComplete = { _, _, _ -> done = true }, onErrorCallback = { fail(it.message) }
        )
        assertTrue(done)
    }

    @Test
    fun aGenerationThatRunsTooLongIsStoppedAndReportedAndTheLockIsFree() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = newManager(factory)
        manager.loadModel(modelFile2(), "CPU")
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        withContext(Dispatchers.Default) {
            manager.generateConversationResponseAsync(
                ConversationState(conversationId = "t", apiToken = "t", ttlMs = 0), listOf(userChat("one")),
                onToken = {}, onComplete = { _, _, _ -> fail("must not complete") }, onErrorCallback = { errors.add(it) },
                generationTimeoutMs = 150
            )
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0] is GenerationTimeoutException)
        assertEquals(1, factory.engine.conversations[0].cancelCount)
        assertTrue(factory.engine.conversations[0].closed)
        factory.engine.hangUntilCancelled = false
        var done = false
        manager.generateConversationResponseAsync(
            ConversationState(conversationId = "n", apiToken = "t", ttlMs = 0), listOf(userChat("two")),
            onToken = {}, onComplete = { _, _, _ -> done = true }, onErrorCallback = { fail(it.message) }
        )
        assertTrue(done)
    }

    @Test
    fun theReadinessPingHasNoLimits() = runTest {
        val manager = newManager(FakeEngineFactory())
        manager.loadModel(modelFile2(), "CPU")
        assertEquals(null, manager.verifyReady(attempts = 1, retryDelayMs = 0))
    }
}
