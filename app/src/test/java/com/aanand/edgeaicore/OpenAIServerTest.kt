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
        val load = client.get("/v1/load") { header(HttpHeaders.Authorization, "Bearer $validToken") }.bodyAsText().replace(" ", "")
        assertTrue(load, load.contains("\"load\":0"))
        // Plain text still works on a text-only model
        val ok = client.post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $validToken")
            header(HttpHeaders.ContentType, "application/json")
            setBody(chatBody)
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }
}
