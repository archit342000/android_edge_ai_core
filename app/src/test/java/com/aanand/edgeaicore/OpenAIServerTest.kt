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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenAIServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val validToken = "good-token"

    private fun loadedManager(): AiEngineManager {
        val manager = AiEngineManager(FakeEngineFactory(chunks = listOf("po", "ng")))
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
    fun requestsWithoutValidTokenAreRejected() = withServer(AiEngineManager(FakeEngineFactory())) {
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
        withServer(AiEngineManager(FakeEngineFactory())) {
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
        val load = client.get("/v1/load") { header(HttpHeaders.Authorization, "Bearer $validToken") }.bodyAsText().replace(" ", "")
        assertTrue(load, load.contains("\"load\":0"))
    }

    @Test
    fun validLimitsReachTheEngine() {
        val factory = FakeEngineFactory(chunks = listOf("ok"))
        val manager = AiEngineManager(factory)
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
}
