package com.aanand.edgeaicore

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenAIServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val validToken = "good-token"

    private fun loadedManager(): AiEngineManager {
        val manager = newManager(FakeEngineFactory(chunks = listOf("po", "ng")))
        runBlocking { manager.loadModel(tmp.newFile("model.litertlm").absolutePath, "CPU") }
        return manager
    }

    private fun server(manager: AiEngineManager) = OpenAIServer(manager) { it == validToken }

    private fun withServer(manager: AiEngineManager, block: suspend ApplicationTestBuilder.() -> Unit) {
        val server = server(manager)
        testApplication {
            application { with(server) { module() } }
            block()
        }
    }

    private val chatBody = """{"messages":[{"role":"user","content":"ping"}]}"""
    private val streamBody = """{"stream":true,"messages":[{"role":"user","content":"ping"}]}"""

    @Test
    fun requestsWithoutValidTokenAreRejected() = withServer(newManager(FakeEngineFactory())) {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/load").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models").status)
        val chat = client.post("/v1/chat/completions") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(chatBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, chat.status)
        val wrong = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer nope") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun healthIs503BeforeLoadAnd200After() {
        withServer(newManager(FakeEngineFactory())) {
            val auth = { b: io.ktor.client.request.HttpRequestBuilder -> b.header(HttpHeaders.Authorization, "Bearer $validToken") }
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/health", auth).status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health", auth).status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v1/load", auth).status)
        }
        withServer(loadedManager()) {
            val auth = { b: io.ktor.client.request.HttpRequestBuilder -> b.header(HttpHeaders.Authorization, "Bearer $validToken") }
            val v1 = client.get("/v1/health", auth)
            assertEquals(HttpStatusCode.OK, v1.status)
            val v1Body = v1.bodyAsText().replace(" ", "")
            assertTrue(v1Body.contains("\"ok\""))
            assertTrue(v1Body, v1Body.contains("\"backend\":\"CPU\""))
            val root = client.get("/health", auth)
            assertEquals(HttpStatusCode.OK, root.status)
            assertTrue(root.bodyAsText().replace(" ", "").contains("\"backend\":\"CPU\""))
        }
    }

    @Test
    fun loadReportsActiveRequestsWhenModelLoaded() = withServer(loadedManager()) {
        val response = client.get("/v1/load") { header(HttpHeaders.Authorization, "Bearer $validToken") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().replace(" ", "").contains("\"load\":0"))
    }

    @Test
    fun nonStreamingChatReturnsReply() = withServer(loadedManager()) {
        val response = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(chatBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text, text.contains("pong"))
        assertTrue(text, text.contains("\"stop\""))
    }

    @Test
    fun streamingChatEndsWithDone() = withServer(loadedManager()) {
        val response = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(streamBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text, text.contains("\"po\""))
        assertTrue(text, text.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun unparsableBodyReturns400() = withServer(loadedManager()) {
        val response = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private suspend fun ApplicationTestBuilder.loadCount(): String =
        client.get("/v1/load") { header(HttpHeaders.Authorization, "Bearer $validToken") }
            .bodyAsText().replace(" ", "")

    /** The route decrements the counter after the response is sent, so poll (bounded) instead of reading once. */
    private suspend fun ApplicationTestBuilder.awaitLoadZero() {
        var text = loadCount()
        var attempts = 0
        while (!text.contains("\"load\":0") && attempts++ < 100) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(20) }
            text = loadCount()
        }
        assertTrue(text, text.contains("\"load\":0"))
    }

    @Test
    fun loadReturnsToZeroAfterStreamingAndBadRequests() = withServer(loadedManager()) {
        client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(streamBody)
        }.bodyAsText()
        client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody("not json")
        }.bodyAsText()
        client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(chatBody)
        }.bodyAsText()
        awaitLoadZero()
    }

    @Test
    fun streamWithoutModelSendsErrorEventWithoutDone() {
        val manager = newManager(FakeEngineFactory())
        withServer(manager) {
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody(streamBody)
            }
            val text = response.bodyAsText()
            assertTrue(text, text.contains("Model not loaded"))
            assertTrue(text, !text.contains("[DONE]"))
        }
    }

    @Test
    fun aFailedWriteCancelsTheGenerationJob() = runBlocking {
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = kotlinx.coroutines.Job()
        channel.trySend("one")
        channel.trySend("two")
        val written = mutableListOf<String>()
        OpenAIServer.forwardChunks(channel, job) { msg ->
            if (written.size == 1) throw java.io.IOException("Broken pipe")
            written.add(msg)
        }
        assertEquals(listOf("one"), written)
        assertTrue(job.isCancelled)
    }

    @Test
    fun aClosedChannelFailureCancelsTheJobAndRethrows() = runBlocking {
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = kotlinx.coroutines.Job()
        channel.trySend("one")
        try {
            OpenAIServer.forwardChunks(channel, job) { throw kotlinx.coroutines.CancellationException("closed") }
            org.junit.Assert.fail("expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun aHealthyStreamIsForwardedInOrderAndDoesNotCancelTheJob() = runBlocking {
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = kotlinx.coroutines.Job()
        channel.trySend("a"); channel.trySend("b"); channel.close()
        val written = mutableListOf<String>()
        OpenAIServer.forwardChunks(channel, job) { written.add(it) }
        assertEquals(listOf("a", "b"), written)
        assertTrue(!job.isCancelled)
    }

    @Test
    fun aDroppedStreamCancelsTheEngineAndFreesTheLock() = runBlocking {
        val factory = FakeEngineFactory(hangUntilCancelled = true)
        val manager = newManager(factory)
        manager.loadModel(tmp.newFile("m.litertlm").absolutePath, "CPU")
        val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val state = ConversationState(conversationId = "c1", apiToken = "t", ttlMs = 0)
        val message = ChatMessage("user", com.google.gson.JsonPrimitive("hi"))

        kotlinx.coroutines.coroutineScope {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                manager.generateConversationResponseAsync(state, listOf(message), { channel.trySend(it) }, { _, _ -> }, {})
            }
            kotlinx.coroutines.withTimeout(5_000) {
                while (factory.engine.conversations.firstOrNull()?.sent != 1) kotlinx.coroutines.delay(5)
            }
            channel.trySend("first token")
            // The client has gone: the first write fails.
            OpenAIServer.forwardChunks(channel, job) { throw java.io.IOException("Broken pipe") }
            job.join()
        }

        assertEquals(1, factory.engine.conversations[0].cancelCount)
        assertTrue(factory.engine.conversations[0].closed)
        // The next request runs straight away.
        factory.engine.hangUntilCancelled = false
        var response = ""
        manager.generateConversationResponseAsync(
            ConversationState(conversationId = "c2", apiToken = "t", ttlMs = 0),
            listOf(message), {}, { text, _ -> response = text }, { org.junit.Assert.fail("unexpected ${it.message}") }
        )
        assertEquals("Hello", response)
    }

    private fun usageManager(counts: Triple<Int, Int, Int> = Triple(7, 3, 10), usageFailure: Throwable? = null): AiEngineManager {
        val manager = newManager(FakeEngineFactory(chunks = listOf("po", "ng"), counts = counts, usageFailure = usageFailure))
        runBlocking { manager.loadModel(tmp.newFile("model-${System.nanoTime()}.litertlm").absolutePath, "CPU") }
        return manager
    }

    @Test
    fun nonStreamingResponseCarriesTheEngineUsage() = withServer(usageManager(Triple(7, 3, 12))) {
        val text = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(chatBody)
        }.bodyAsText().replace(" ", "").replace("\n", "")
        assertTrue(text, text.contains("\"prompt_tokens\":9"))
        assertTrue(text, text.contains("\"completion_tokens\":3"))
        assertTrue(text, text.contains("\"total_tokens\":12"))
        assertTrue(text, text.contains("\"cached_tokens\":2"))
    }

    @Test
    fun streamWithIncludeUsageEndsWithAUsageChunkThenDone() = withServer(usageManager()) {
        val text = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"ping"}]}""")
        }.bodyAsText()
        val events = text.trim().split("\n\n")
        assertEquals("data: [DONE]", events.last())
        assertTrue(text, events[events.size - 2].contains("\"usage\"") && events[events.size - 2].contains("\"choices\":[]"))
        assertTrue(text, events[events.size - 2].contains("\"prompt_tokens\":7"))
    }

    @Test
    fun streamWithoutIncludeUsageHasNoUsageChunk() = withServer(usageManager()) {
        val text = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(streamBody)
        }.bodyAsText()
        assertTrue(text, !text.contains("usage"))
        assertTrue(text, text.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun unreadableUsageGivesTheFixed500() = withServer(usageManager(usageFailure = IllegalStateException("secret detail"))) {
        val response = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(chatBody)
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val text = response.bodyAsText()
        assertTrue(text, text.contains("Token usage unavailable"))
        assertTrue(text, !text.contains("secret detail"))
    }

    @Test
    fun unreadableUsageOnAStreamSendsTheErrorEventWithoutDone() = withServer(usageManager(usageFailure = IllegalStateException("secret detail"))) {
        val text = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(streamBody)
        }.bodyAsText()
        assertTrue(text, text.contains("Token usage unavailable"))
        assertTrue(text, !text.contains("[DONE]") && !text.contains("secret detail"))
    }

    @Test
    fun aSystemMessageBecomesTheSystemInstructionNotAUserTurn() {
        val factory = FakeEngineFactory(chunks = listOf("ok"))
        val manager = newManager(factory)
        runBlocking { manager.loadModel(tmp.newFile("m-sys.litertlm").absolutePath, "CPU") }
        withServer(manager) {
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"messages":[{"role":"system","content":"Always answer in French."},{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals("Always answer in French.", factory.engine.conversationConfigs.last().systemInstructionText())
    }

    @Test
    fun withoutASystemMessageNoInstructionIsSent() {
        val factory = FakeEngineFactory(chunks = listOf("ok"))
        val manager = newManager(factory)
        runBlocking { manager.loadModel(tmp.newFile("m-nosys.litertlm").absolutePath, "CPU") }
        withServer(manager) {
            client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody(chatBody)
            }.bodyAsText()
        }
        assertNull(factory.engine.conversationConfigs.last().systemInstructionText())
    }

    @Test
    fun aSystemOnlyRequestIsRejectedWith400AndTheLoadCounterReturnsToZero() = withServer(loadedManager()) {
        val response = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"messages":[{"role":"system","content":"Only a system message."}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("messages must include a non-system message"))
        awaitLoadZero()
    }

    @Test
    fun anEmptyMessagesArrayIsRejectedWith400OnBothPaths() = withServer(loadedManager()) {
        for (body in listOf("""{"messages":[]}""", """{"stream":true,"messages":[]}""")) {
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }
            assertEquals(body, HttpStatusCode.BadRequest, response.status)
        }
        awaitLoadZero()
    }

    @Test
    fun everyInvalidFieldGivesItsFixed400AndTheLoadCounterReturnsToZero() = withServer(loadedManager()) {
        val cases = mapOf(
            """{"messages":[{"role":"user","content":"hi"}],"temperature":5}""" to "temperature must be between 0 and 2.0",
            """{"messages":[{"role":"user","content":"hi"}],"max_tokens":0}""" to "max_tokens must be at least 1",
            """{"messages":[{"role":"user","content":"hi"}],"top_p":0}""" to "top_p must be greater than 0 and at most 1",
            """{"messages":[{"role":"user","content":"hi"}],"presence_penalty":3}""" to "presence_penalty must be between -2.0 and 2.0",
            """{"stream":true,"messages":[{"role":"user","content":"hi"}],"top_k":0}""" to "top_k must be at least 1"
        )
        for ((body, message) in cases) {
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }
            assertEquals(body, HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText(), response.bodyAsText().contains(message))
        }
        awaitLoadZero()
    }

    @Test
    fun validLimitsReachTheEngine() {
        val factory = FakeEngineFactory(chunks = listOf("ok"))
        val manager = newManager(factory)
        runBlocking { manager.loadModel(tmp.newFile("m-lim.litertlm").absolutePath, "CPU") }
        withServer(manager) {
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"messages":[{"role":"user","content":"hi"}],"max_tokens":5,"max_completion_tokens":9,"presence_penalty":0.5,"frequency_penalty":-1,"seed":42}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        val conversation = factory.engine.conversations.last()
        assertEquals(GenerationOptions(maxOutputTokens = 9, presencePenalty = 0.5f, frequencyPenalty = -1f), conversation.lastOptions)
        assertEquals(42, factory.engine.conversationSeeds.last())
    }

    private fun managerWith(factory: FakeEngineFactory): AiEngineManager {
        val manager = newManager(factory)
        runBlocking { manager.loadModel(tmp.newFile("m-${System.nanoTime()}.litertlm").absolutePath, "CPU") }
        return manager
    }

    private suspend fun ApplicationTestBuilder.chat(body: String) = client.post("/v1/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer $validToken")
        header(HttpHeaders.ContentType, "application/json")
        setBody(body)
    }

    private val weather = """{"type":"function","function":{"name":"get_weather","description":"d","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}"""

    @Test
    fun aToolCallReplyHasFinishReasonToolCallsAndNullContent() {
        val factory = FakeEngineFactory(chunks = emptyList(), toolCalls = listOf(com.google.ai.edge.litertlm.ToolCall("get_weather", mapOf("city" to "Paris"))))
        withServer(managerWith(factory)) {
            val response = chat("""{"messages":[{"role":"user","content":"weather in Paris?"}],"tools":[$weather]}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText().replace(" ", "").replace("\n", "")
            assertTrue(text, text.contains("\"finish_reason\":\"tool_calls\""))
            assertTrue(text, text.contains("\"name\":\"get_weather\""))
            assertTrue(text, text.contains("\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\""))
            assertTrue(text, text.contains("\"id\":\"call_"))
            assertFalse(text, text.contains("\"content\""))
            assertFalse(text, text.contains("\"tool_calls\":[{\"index\"")) // a tool call's index is for streaming only
        }
    }

    @Test
    fun aStreamedReplyCarriesReasoningToolCallsAndTheFinishReason() {
        val factory = FakeEngineFactory(
            chunks = emptyList(), thinking = "hmm",
            toolCalls = listOf(com.google.ai.edge.litertlm.ToolCall("get_weather", mapOf("city" to "Paris")))
        )
        withServer(managerWith(factory)) {
            val text = chat("""{"stream":true,"reasoning_effort":"high","messages":[{"role":"user","content":"weather?"}],"tools":[$weather]}""").bodyAsText()
            assertTrue(text, text.contains("\"reasoning_content\":\"hmm\""))
            assertTrue(text, text.contains("\"tool_calls\":[{\"index\":0,\"id\":\"call_"))
            assertTrue(text, text.contains("\"finish_reason\":\"tool_calls\""))
            assertTrue(text, text.trimEnd().endsWith("data: [DONE]"))
        }
    }

    @Test
    fun aPlainStreamEndsWithAStopChunkThenDone() = withServer(loadedManager()) {
        val text = chat(streamBody).bodyAsText()
        assertTrue(text, text.contains("\"finish_reason\":\"stop\""))
        assertTrue(text, text.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun reasoningIsReturnedSeparatelyFromTheContent() {
        val factory = FakeEngineFactory(chunks = listOf("answer"), thinking = "thinking text")
        withServer(managerWith(factory)) {
            val text = chat("""{"reasoning_effort":"high","messages":[{"role":"user","content":"hi"}]}""").bodyAsText()
            assertTrue(text, text.contains("\"reasoning_content\": \"thinking text\"") || text.contains("\"reasoning_content\":\"thinking text\""))
            assertTrue(text, text.contains("\"content\": \"answer\"") || text.contains("\"content\":\"answer\""))
        }
    }

    @Test
    fun invalidToolRelatedInputGivesFixedMessages400AndTheLoadCounterReturnsToZero() = withServer(loadedManager()) {
        val user = """{"role":"user","content":"hi"}"""
        val cases = mapOf(
            """{"messages":[$user],"tools":[$weather],"tool_choice":"required"}""" to "tool_choice must be",
            """{"messages":[$user,{"role":"tool","tool_call_id":"nope","content":"x"}]}""" to "unknown tool_call_id",
            """{"messages":[$user],"reasoning_effort":"extreme"}""" to "reasoning_effort must be",
            """{"messages":[$user],"response_format":{"type":"xml"}}""" to "response_format.type must be",
            """{"messages":[$user],"response_format":{"type":"json_schema","json_schema":{"schema":"nope"}}}""" to "must be a JSON object",
            """{"messages":[{"role":"assistant","tool_calls":[{"id":"c","function":{"name":"f","arguments":"{broken"}}]},$user]}""" to "JSON object"
        )
        for ((body, message) in cases) {
            val response = chat(body)
            assertEquals(body, HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText(), response.bodyAsText().contains(message))
        }
        awaitLoadZero()
    }

    @Test
    fun invalidToolInputOnAStreamAlsoGivesA400AndReleasesTheLoadCounter() = withServer(loadedManager()) {
        val response = chat("""{"stream":true,"messages":[{"role":"user","content":"hi"}],"tools":[$weather],"tool_choice":"required"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("tool_choice must be"))
        awaitLoadZero()
    }

    @Test
    fun toolsTogetherWithAResponseFormatAreRejected() = withServer(loadedManager()) {
        val response = chat("""{"messages":[{"role":"user","content":"hi"}],"tools":[$weather],"response_format":{"type":"json_object"}}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("cannot be combined"))
    }

    private fun textOnlyManager(): AiEngineManager {
        val manager = newManager(FakeEngineFactory()) { FULL_LLM.copy(supportsVision = false, supportsAudio = false, visionBackends = null, audioBackends = null) }
        runBlocking { manager.loadModel(tmp.newFile("text-only-${System.nanoTime()}.litertlm").absolutePath, "CPU") }
        return manager
    }

    @Test
    fun imageAndAudioInputToATextOnlyModelGetFixed400sAndTheLoadCounterReturnsToZero() = withServer(textOnlyManager()) {
        val image = """{"messages":[{"role":"user","content":[{"type":"text","text":"x"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]}"""
        val audio = """{"stream":true,"messages":[{"role":"user","content":[{"type":"audio_url","audio_url":{"url":"data:audio/wav;base64,AAAA"}}]}]}"""
        for ((body, message) in listOf(image to "Model does not support image input", audio to "Model does not support audio input")) {
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $validToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains(message))
        }
        awaitLoadZero()
        // Plain text still works on a text-only model
        val ok = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(chatBody)
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }
}
