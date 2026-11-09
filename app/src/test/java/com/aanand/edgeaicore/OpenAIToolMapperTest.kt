package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAIToolMapperTest {

    private fun tool(name: String? = "get_weather") = ToolDto(
        "function",
        ToolFunctionDto(name, "Weather for a city", JsonParser.parseString("""{"type":"object","properties":{"city":{"type":"string"}}}"""))
    )

    private fun invalid(block: () -> Unit): String {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            return e.message ?: ""
        }
        return ""
    }

    @Test
    fun toolsAreOfferedForAutoAndAbsentChoiceButNotForNone() {
        assertEquals(1, OpenAIToolMapper.toolProviders(listOf(tool()), null).size)
        assertEquals(1, OpenAIToolMapper.toolProviders(listOf(tool()), JsonPrimitive("auto")).size)
        assertEquals(0, OpenAIToolMapper.toolProviders(listOf(tool()), JsonPrimitive("none")).size)
        assertEquals(0, OpenAIToolMapper.toolProviders(null, null).size)
    }

    @Test
    fun invalidToolInputIsRejectedWithFixedMessages() {
        assertTrue(invalid { OpenAIToolMapper.toolProviders(listOf(tool()), JsonPrimitive("required")) }.contains("tool_choice"))
        assertTrue(invalid { OpenAIToolMapper.toolProviders(listOf(tool()), JsonObject()) }.contains("tool_choice"))
        assertTrue(invalid { OpenAIToolMapper.toolProviders(listOf(tool(name = " ")), null) }.contains("function name"))
        assertTrue(invalid { OpenAIToolMapper.toolProviders(listOf(ToolDto("retrieval", null)), null) }.isNotEmpty())
        assertTrue(invalid { OpenAIToolMapper.toolProviders(List(OpenAIToolMapper.MAX_TOOLS + 1) { tool() }, null) }.contains("at most"))
    }

    @Test
    fun toolCallArgumentsAreParsedFromTheJsonString() {
        val call = OpenAIToolMapper.toToolCall(ToolCallDto(id = "call_1", function = FunctionCallDto("get_weather", """{"city":"Paris","days":3,"metric":true}""")))
        assertEquals("get_weather", call.name)
        assertEquals(mapOf("city" to "Paris", "days" to 3L, "metric" to true), call.arguments)
    }

    @Test
    fun badToolCallArgumentsAreRejected() {
        assertTrue(invalid { OpenAIToolMapper.toToolCall(ToolCallDto(function = FunctionCallDto("f", "{not json"))) }.contains("JSON object"))
        assertTrue(invalid { OpenAIToolMapper.toToolCall(ToolCallDto(function = FunctionCallDto("f", "[1,2]"))) }.contains("JSON object"))
        assertTrue(invalid { OpenAIToolMapper.toToolCall(ToolCallDto(function = FunctionCallDto(null, "{}"))) }.contains("function name"))
        assertEquals(emptyMap<String, Any?>(), OpenAIToolMapper.toToolCall(ToolCallDto(function = FunctionCallDto("f", ""))).arguments)
    }

    @Test
    fun aToolMessageNeedsAKnownToolCallId() {
        val assistant = ChatMessage("assistant", null, listOf(ToolCallDto(id = "call_1", function = FunctionCallDto("get_weather", "{}"))))
        val good = ChatMessage("tool", JsonPrimitive("""{"temp":20}"""), tool_call_id = "call_1")
        OpenAIToolMapper.validateMessages(listOf(ChatMessage("user", JsonPrimitive("hi")), assistant, good))
        val bad = ChatMessage("tool", JsonPrimitive("x"), tool_call_id = "call_unknown")
        assertTrue(invalid { OpenAIToolMapper.validateMessages(listOf(assistant, bad)) }.contains("tool_call_id"))
        assertTrue(invalid { OpenAIToolMapper.validateMessages(listOf(ChatMessage("tool", JsonPrimitive("x")))) }.contains("tool_call_id"))
    }

    @Test
    fun toolCallIdsRoundTripToOpenAiToolCalls() {
        val dtos = OpenAIToolMapper.toOpenAiToolCalls(listOf(ToolCall("get_weather", mapOf("city" to "Paris")), ToolCall("other", emptyMap())))
        assertEquals(2, dtos.size)
        assertTrue(dtos[0].id!!.startsWith("call_"))
        assertTrue(dtos[0].id != dtos[1].id)
        assertEquals("function", dtos[0].type)
        assertEquals("""{"city":"Paris"}""", dtos[0].function!!.arguments)
        assertEquals(listOf(0, 1), dtos.map { it.index })
        assertEquals(mapOf(dtos[0].id!! to "get_weather", dtos[1].id!! to "other"),
            OpenAIToolMapper.toolCallNames(listOf(ChatMessage("assistant", null, dtos))))
    }

    @Test
    fun responseFormatMapsEachType() {
        assertNull(OpenAIToolMapper.responseFormat(null))
        assertNull(OpenAIToolMapper.responseFormat(ResponseFormatDto("text")))
        assertNotNull(OpenAIToolMapper.responseFormat(ResponseFormatDto("json_object")))
        val schema = JsonParser.parseString("""{"type":"object","properties":{"name":{"type":"string"}}}""")
        assertNotNull(OpenAIToolMapper.responseFormat(ResponseFormatDto("json_schema", JsonSchemaDto("person", schema))))
    }

    @Test
    fun invalidResponseFormatsAreRejected() {
        assertTrue(invalid { OpenAIToolMapper.responseFormat(ResponseFormatDto("xml")) }.contains("response_format.type"))
        assertTrue(invalid { OpenAIToolMapper.responseFormat(ResponseFormatDto("json_schema")) }.contains("schema"))
        assertTrue(invalid { OpenAIToolMapper.responseFormat(ResponseFormatDto("json_schema", JsonSchemaDto("p", JsonPrimitive("nope")))) }.contains("schema"))
        val huge = JsonObject().apply { addProperty("description", "x".repeat(OpenAIToolMapper.MAX_SCHEMA_CHARS)) }
        assertTrue(invalid { OpenAIToolMapper.responseFormat(ResponseFormatDto("json_schema", JsonSchemaDto("p", huge))) }.contains("too large"))
    }

    @Test
    fun reasoningEffortMapsToThinkingConfig() {
        assertNull(OpenAIToolMapper.thinkingConfig(null))
        assertEquals(false, OpenAIToolMapper.thinkingConfig("none")!!.enableThinking)
        for (effort in listOf("minimal", "low", "medium", "high", "xhigh")) {
            val config = OpenAIToolMapper.thinkingConfig(effort)!!
            assertEquals(effort, true, config.enableThinking)
            assertEquals(-1, config.thinkingTokenBudget)
        }
        assertTrue(invalid { OpenAIToolMapper.thinkingConfig("extreme") }.contains("reasoning_effort"))
    }

    @Test
    fun prepareCombinesEverythingAndKeysTheTools() {
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", JsonPrimitive("hi"))),
            tools = listOf(tool()), reasoning_effort = "high"
        )
        val options = OpenAIToolMapper.prepare(request)
        assertEquals(1, options.tools.size)
        assertNotNull(options.toolsKey)
        assertEquals(true, options.thinking!!.enableThinking)
        assertEquals(false, options.responseFormatEnabled)
        assertTrue(OpenAIToolMapper.prepare(ChatCompletionRequest(messages = request.messages, response_format = ResponseFormatDto("json_object"))).responseFormatEnabled)
        val plain = OpenAIToolMapper.prepare(ChatCompletionRequest(messages = request.messages))
        assertNull(plain.toolsKey)
        assertEquals(false, plain.responseFormatEnabled)
    }

    @Test
    fun aNullToolChoiceMeansAbsent() {
        assertEquals(1, OpenAIToolMapper.toolProviders(listOf(tool()), com.google.gson.JsonNull.INSTANCE).size)
    }

    @Test
    fun nullEntriesInToolsOrMessagesAreRejectedWithFixedMessages() {
        val tools = Gson().fromJson("""{"messages":[{"role":"user","content":"hi"}],"tools":[null]}""", ChatCompletionRequest::class.java)
        assertTrue(invalid { OpenAIToolMapper.prepare(tools) }.contains("tools must not contain null"))
        val messages = Gson().fromJson("""{"messages":[null]}""", ChatCompletionRequest::class.java)
        assertTrue(invalid { OpenAIToolMapper.prepare(messages) }.contains("messages must not contain null"))
    }

    @Test
    fun oversizedToolCallArgumentsAndToolResultsAreRejected() {
        val big = "x".repeat(OpenAIToolMapper.MAX_TOOL_TEXT_CHARS + 1)
        assertTrue(invalid { OpenAIToolMapper.toToolCall(ToolCallDto(function = FunctionCallDto("f", big))) }.contains("too large"))
        val names = mapOf("call_1" to "f")
        assertTrue(invalid { OpenAIToolMapper.toToolMessage(ChatMessage("tool", JsonPrimitive(big), tool_call_id = "call_1"), names) }.contains("too large"))
    }

    @Test
    fun aToolResultIsParsedAsJsonWhenItIsAndKeptAsTextOtherwise() {
        val names = mapOf("call_1" to "get_weather")
        fun response(content: com.google.gson.JsonElement?) =
            OpenAIToolMapper.toToolMessage(ChatMessage("tool", content, tool_call_id = "call_1"), names)
                .contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.ToolResponse>().single()
        assertEquals(mapOf("temp" to 20L), response(JsonPrimitive("""{"temp":20}""")).response)
        assertEquals("sunny, 20 degrees", response(JsonPrimitive("sunny, 20 degrees")).response)
        assertEquals("", response(null).response)
        assertEquals("", response(com.google.gson.JsonNull.INSTANCE).response)
        assertEquals("get_weather", response(JsonPrimitive("x")).name)
    }

    @Test
    fun responseFormatCannotBeCombinedWithTools() {
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", JsonPrimitive("hi"))),
            tools = listOf(tool()), response_format = ResponseFormatDto("json_object")
        )
        assertTrue(invalid { OpenAIToolMapper.prepare(request) }.contains("cannot be combined"))
        // Either alone is fine, and tool_choice none leaves no tools, so the combination is allowed then
        OpenAIToolMapper.prepare(request.copy(tools = null))
        OpenAIToolMapper.prepare(request.copy(tool_choice = JsonPrimitive("none")))
    }
}
