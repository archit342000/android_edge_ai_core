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
import org.junit.Assert.assertFalse
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

    private fun managerWith(factory: FakeEngineFactory): AiEngineManager {
        val manager = AiEngineManager(factory)
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
        val load = client.get("/v1/load") { header(HttpHeaders.Authorization, "Bearer $validToken") }.bodyAsText().replace(" ", "")
        assertTrue(load, load.contains("\"load\":0"))
    }

    @Test
    fun invalidToolInputOnAStreamAlsoGivesA400AndReleasesTheLoadCounter() = withServer(loadedManager()) {
        val response = chat("""{"stream":true,"messages":[{"role":"user","content":"hi"}],"tools":[$weather],"tool_choice":"required"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("tool_choice must be"))
        val load = client.get("/v1/load") { header(HttpHeaders.Authorization, "Bearer $validToken") }.bodyAsText().replace(" ", "")
        assertTrue(load, load.contains("\"load\":0"))
    }

    @Test
    fun toolsTogetherWithAResponseFormatAreRejected() = withServer(loadedManager()) {
        val response = chat("""{"messages":[{"role":"user","content":"hi"}],"tools":[$weather],"response_format":{"type":"json_object"}}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("cannot be combined"))
    }
}
