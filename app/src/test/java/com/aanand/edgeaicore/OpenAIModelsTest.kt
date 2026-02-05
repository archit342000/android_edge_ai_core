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
}
