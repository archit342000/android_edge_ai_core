package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Backend
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
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
            state(), listOf(userMessage("a")), onToken = {}, onComplete = {}, onErrorCallback = {}
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
        options: ChatOptions = ChatOptions()
    ) = generateConversationResponseAsync(
        ConversationState(conversationId = "req-${System.nanoTime()}", apiToken = "t", ttlMs = 0),
        messages, onToken = {}, onComplete = reply, onErrorCallback = error, options = options
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
    fun cancellingMidGenerationClosesTheConversationOnceAndDoesNotCacheOrReport() = runTest {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        var reported = 0
        val job = launch(Dispatchers.Default) {
            manager.request(listOf(userChat("one")), reply = { reported++ }, error = { reported++ })
        }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (factory.engine.conversations.firstOrNull()?.sent != 1) delay(5) }
        }
        job.cancelAndJoin()
        assertEquals(0, reported)
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
            manager.generateConversationResponseAsync(state, messages, onToken = {}, onComplete = {}, onErrorCallback = {})
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

    private val weatherTool = ToolDto("function", ToolFunctionDto("get_weather", "d", null))

    @Test
    fun toolsReachTheConversationConfigWithManualToolCalling() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val options = OpenAIToolMapper.prepare(ChatCompletionRequest(messages = listOf(userChat("hi")), tools = listOf(weatherTool)))
        manager.request(listOf(userChat("hi")), options = options)
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
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val calls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()
        val reasoning = StringBuilder()
        manager.generateConversationResponseAsync(
            ConversationState(conversationId = "x", apiToken = "t", ttlMs = 0), listOf(userChat("hi")),
            onToken = {}, onComplete = {}, onErrorCallback = { fail(it.message) },
            onReasoning = { reasoning.append(it) }, onToolCalls = { calls.addAll(it) }
        )
        assertEquals("get_weather", calls.single().name)
        assertEquals("let me think", reasoning.toString())
    }

    @Test
    fun perRequestResponseFormatAndThinkingReachTheSend() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val options = OpenAIToolMapper.prepare(
            ChatCompletionRequest(messages = listOf(userChat("hi")), response_format = ResponseFormatDto("json_object"), reasoning_effort = "high")
        )
        manager.request(listOf(userChat("hi")), options = options)
        val conversation = factory.engine.conversations[0]
        assertEquals(true, conversation.lastResponseFormat != null)
        assertEquals(true, conversation.lastThinking!!.enableThinking)
        assertEquals(true, factory.engine.conversationConfigs[0].enableResponseFormat)
    }

    @Test
    fun aToolResultInTheHistoryBecomesAToolMessage() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
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
        val manager = AiEngineManager(factory)
        manager.loadModel(modelFile(), "CPU")
        val withTools = OpenAIToolMapper.prepare(ChatCompletionRequest(messages = listOf(userChat("hi")), tools = listOf(weatherTool)))
        manager.request(listOf(userChat("one")), options = withTools)
        manager.request(listOf(userChat("one"), assistantChat("Hello"), userChat("two")))
        assertEquals(2, factory.engine.conversations.size)
    }

    @Test
    fun anAssistantTurnWithNullContentAndToolCallsMapsWithoutANullText() = runTest {
        val factory = FakeEngineFactory()
        val manager = AiEngineManager(factory)
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
}
