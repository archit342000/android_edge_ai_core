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
}
