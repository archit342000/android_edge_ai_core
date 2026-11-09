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
            assertTrue(v1.bodyAsText().contains("\"ok\""))
            assertEquals(HttpStatusCode.OK, client.get("/health", auth).status)
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
}
