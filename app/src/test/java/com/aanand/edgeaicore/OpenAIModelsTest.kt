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
}
