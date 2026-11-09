package com.aanand.edgeaicore

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
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
