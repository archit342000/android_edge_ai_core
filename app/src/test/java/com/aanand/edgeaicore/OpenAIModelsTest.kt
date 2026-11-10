package com.aanand.edgeaicore

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIModelsTest {
    @Test
    fun testSerialization() {
        val request = ChatCompletionRequest(
            model = "gemma",
            messages = listOf(
                ChatMessage("user", JsonPrimitive("Hello"))
            )
        )
        val gson = Gson()
        val json = gson.toJson(request)
        // Check if json contains "Hello"
        assert(json.contains("Hello"))
        assert(json.contains("user"))
        assert(json.contains("gemma"))
    }

    @Test
    fun tokenUsageForAFreshConversationHasNoCachedTokens() {
        val usage = TokenUsage.from(prefillTokens = 12, decodeTokens = 5, kvTokenCount = 17)
        assertEquals(TokenUsage(promptTokens = 12, completionTokens = 5, cachedTokens = 0), usage)
        assertEquals(17, usage.totalTokens)
    }

    @Test
    fun tokenUsageForAReusedConversationCountsCachedTokensInThePrompt() {
        val usage = TokenUsage.from(prefillTokens = 4, decodeTokens = 6, kvTokenCount = 30)
        assertEquals(20, usage.cachedTokens)
        assertEquals(24, usage.promptTokens)
        assertEquals(30, usage.totalTokens)
    }

    @Test
    fun tokenUsageClampsANegativeCachedDifference() {
        val usage = TokenUsage.from(prefillTokens = 10, decodeTokens = 10, kvTokenCount = 5)
        assertEquals(0, usage.cachedTokens)
        assertEquals(10, usage.promptTokens)
    }

    @Test
    fun requestParsesStreamOptionsIncludeUsage() {
        val request = Gson().fromJson(
            """{"messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true}}""",
            ChatCompletionRequest::class.java
        )
        assertEquals(true, request.stream_options?.include_usage)
        val plain = Gson().fromJson("""{"messages":[{"role":"user","content":"hi"}]}""", ChatCompletionRequest::class.java)
        assertEquals(null, plain.stream_options)
    }

    @Test
    fun usageSerialisesWithCachedTokenDetails() {
        val json = Gson().toJson(TokenUsage(promptTokens = 24, completionTokens = 6, cachedTokens = 20).toUsage())
        assertTrue(json.contains("\"prompt_tokens\":24"))
        assertTrue(json.contains("\"total_tokens\":30"))
        assertTrue(json.contains("\"cached_tokens\":20"))
    }

    @Test
    fun aChunkWithoutUsageHasNoUsageKeyAndTheUsageChunkHasEmptyChoices() {
        val delta = ChunkDelta(content = "x")
        val plain = Gson().toJson(
            ChatCompletionChunk(id = "a", created = 1, model = "m", choices = listOf(ChunkChoice(0, delta, null)))
        )
        assertFalse(plain.contains("usage"))
        val last = Gson().toJson(
            ChatCompletionChunk(id = "a", created = 1, model = "m", choices = emptyList(), usage = TokenUsage(2, 1, 0).toUsage())
        )
        assertTrue(last.contains("\"choices\":[]"))
        assertTrue(last.contains("\"usage\""))
    }

    @Test
    fun requestParsesTheGenerationLimitFields() {
        val request = Gson().fromJson(
            """{"messages":[{"role":"user","content":"hi"}],"max_tokens":5,"max_completion_tokens":9,"presence_penalty":0.5,"frequency_penalty":-1,"seed":42}""",
            ChatCompletionRequest::class.java
        )
        assertEquals(5, request.max_tokens)
        assertEquals(9, request.max_completion_tokens)
        assertEquals(0.5, request.presence_penalty!!, 0.0)
        assertEquals(-1.0, request.frequency_penalty!!, 0.0)
        assertEquals(42, request.seed)
        assertEquals(9, request.maxOutputTokens)
    }

    @Test
    fun healthResponseSerialisesTheBackend() {
        val json = Gson().toJson(HealthResponse("ok", "CPU"))
        assertEquals("""{"status":"ok","backend":"CPU"}""", json)
    }

    @Test
    fun requestParsesToolsToolChoiceResponseFormatAndReasoningEffort() {
        val request = Gson().fromJson(
            """{"messages":[{"role":"user","content":"hi"}],
               "tools":[{"type":"function","function":{"name":"get_weather","description":"d","parameters":{"type":"object"}}}],
               "tool_choice":"auto","reasoning_effort":"high",
               "response_format":{"type":"json_schema","json_schema":{"name":"p","schema":{"type":"object"}}}}""",
            ChatCompletionRequest::class.java
        )
        assertEquals("get_weather", request.tools!![0].function!!.name)
        assertEquals("auto", request.tool_choice!!.asString)
        assertEquals("high", request.reasoning_effort)
        assertEquals("json_schema", request.response_format!!.type)
        assertEquals(true, request.response_format!!.json_schema!!.schema!!.isJsonObject)
    }

    @Test
    fun anAssistantToolCallTurnAndAToolResultParse() {
        val request = Gson().fromJson(
            """{"messages":[
                 {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]},
                 {"role":"tool","tool_call_id":"call_1","content":"{\"ok\":true}"}]}""",
            ChatCompletionRequest::class.java
        )
        // Gson parses "content": null as JsonNull; the engine treats it as absent
        assertTrue(request.messages[0].content == null || request.messages[0].content!!.isJsonNull)
        assertEquals("call_1", request.messages[0].tool_calls!![0].id)
        assertEquals("""{"a":1}""", request.messages[0].tool_calls!![0].function!!.arguments)
        assertEquals("call_1", request.messages[1].tool_call_id)
    }

    @Test
    fun aToolCallResponseSerialisesWithNullContent() {
        val message = ChatMessageResponse("assistant", null, listOf(ToolCallDto(0, "call_1", "function", FunctionCallDto("f", "{}"))), "thinking")
        val json = Gson().toJson(message)
        assertFalse(json, json.contains("\"content\""))
        assertTrue(json, json.contains("\"tool_calls\"") && json.contains("\"reasoning_content\":\"thinking\""))
    }

    @Test
    fun finishReasonPrefersToolCallsThenLengthThenStop() {
        assertEquals("tool_calls", FinishReason.of(true, 99, 5))
        assertEquals("length", FinishReason.of(false, 5, 5))
        assertEquals("length", FinishReason.of(false, 9, 5))
        assertEquals("stop", FinishReason.of(false, 4, 5))
        assertEquals("stop", FinishReason.of(false, 500, null))
    }
}
