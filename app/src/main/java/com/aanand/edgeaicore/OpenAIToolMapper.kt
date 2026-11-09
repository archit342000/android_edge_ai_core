package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.util.UUID

/**
 * Converts between the OpenAI wire types (tools, tool calls, tool results, response format, reasoning effort) and the
 * LiteRT-LM types. Pure; every invalid input throws an [IllegalArgumentException] with a fixed message that the
 * server returns as a 400 (messages never echo the input).
 */
object OpenAIToolMapper {
    const val MAX_TOOLS = 32
    const val MAX_SCHEMA_CHARS = 16_384

    /** Longest accepted tool-call argument string or tool result, in characters. */
    const val MAX_TOOL_TEXT_CHARS = 16_384
    private val gson = Gson()
    private val THINKING_EFFORTS = setOf("minimal", "low", "medium", "high", "xhigh")

    /** Validates and converts everything a request asks for, including its tool-related messages. */
    fun prepare(request: ChatCompletionRequest): ChatOptions {
        val tools = toolProviders(request.tools, request.tool_choice)
        validateMessages(request.messages)
        val responseFormat = responseFormat(request.response_format)
        // With manual tool calling the engine applies the response format to every decode, including the turn that
        // should emit a tool call, so the two cannot be combined.
        require(tools.isEmpty() || responseFormat == null) { "response_format cannot be combined with tools" }
        return ChatOptions(
            tools = tools,
            toolsKey = if (tools.isEmpty()) null else gson.toJson(request.tools),
            thinking = thinkingConfig(request.reasoning_effort),
            responseFormat = responseFormat
        )
    }

    /** `tool_choice` `auto` (or absent) offers the tools; `none` offers none; anything else is rejected. */
    fun toolProviders(tools: List<ToolDto>?, toolChoice: JsonElement?): List<ToolProvider> {
        // Gson parses "tool_choice": null as JsonNull, which means absent
        val explicit = toolChoice?.takeUnless { it.isJsonNull }
        val choice = explicit?.takeIf { it.isJsonPrimitive }?.asString
        require(explicit == null || choice == "auto" || choice == "none") { "tool_choice must be \"auto\" or \"none\"" }
        if (tools.isNullOrEmpty() || choice == "none") return emptyList()
        require(tools.size <= MAX_TOOLS) { "at most $MAX_TOOLS tools are supported" }
        return tools.map { entry ->
            // Gson can put a null into the list although the type says otherwise
            val definition: ToolDto? = entry
            requireNotNull(definition) { "tools must not contain null" }
            require(definition.type == null || definition.type == "function") { "only function tools are supported" }
            val function = definition.function
            require(function != null && !function.name.isNullOrBlank()) { "every tool needs a function name" }
            val description = JsonObject().apply {
                addProperty("name", function.name)
                function.description?.let { addProperty("description", it) }
                function.parameters?.let { add("parameters", it) }
            }
            val descriptionJson = gson.toJson(description)
            require(descriptionJson.length <= MAX_SCHEMA_CHARS) { "a tool definition is too large" }
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString() = descriptionJson
                override fun execute(paramsJsonString: String): String =
                    throw IllegalStateException("Tools are run by the client, never on the device")
            })
        }
    }

    /** Checks the tool calls and tool results in the history, so a bad one gives a 400 before generation. */
    fun validateMessages(messages: List<ChatMessage>) {
        for (entry in messages) {
            val message: ChatMessage? = entry // Gson can put a null into the list
            requireNotNull(message) { "messages must not contain null" }
        }
        val names = toolCallNames(messages)
        for (message in messages) {
            message.tool_calls?.forEach { toToolCall(it) }
            if (message.role.equals("tool", ignoreCase = true)) toolName(message, names)
        }
    }

    /** Maps each tool call id in the assistant messages to its function name. */
    fun toolCallNames(messages: List<ChatMessage>): Map<String, String> =
        messages.flatMap { it.tool_calls.orEmpty() }.mapNotNull { call ->
            val id = call.id
            val name = call.function?.name
            if (id.isNullOrBlank() || name.isNullOrBlank()) null else id to name
        }.toMap()

    /** The tool name a `tool` message answers; an unknown or missing `tool_call_id` is rejected. */
    fun toolName(message: ChatMessage, names: Map<String, String>): String =
        names[message.tool_call_id] ?: throw IllegalArgumentException("tool message has an unknown tool_call_id")

    /** An assistant tool call with its JSON-string arguments parsed into the map LiteRT-LM expects. */
    fun toToolCall(dto: ToolCallDto): ToolCall {
        val name = dto.function?.name
        require(!name.isNullOrBlank()) { "tool call needs a function name" }
        val arguments = dto.function.arguments
        require(arguments == null || arguments.length <= MAX_TOOL_TEXT_CHARS) { "tool call arguments are too large" }
        val parsed = try {
            if (arguments.isNullOrBlank()) JsonObject() else JsonParser.parseString(arguments)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("tool call arguments must be a JSON object")
        }
        require(parsed.isJsonObject) { "tool call arguments must be a JSON object" }
        return ToolCall(name, parsed.asJsonObject.entrySet().associate { it.key to toKotlin(it.value) })
    }

    /** A `tool` message as LiteRT-LM's tool message; the content is parsed as JSON when it is, else kept as text. */
    fun toToolMessage(message: ChatMessage, names: Map<String, String>): Message {
        val name = toolName(message, names)
        val text = message.content?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) } ?: ""
        require(text.length <= MAX_TOOL_TEXT_CHARS) { "tool result is too large" }
        val response: Any? = if (text.isBlank()) text else try {
            toKotlin(JsonParser.parseString(text))
        } catch (e: JsonParseException) {
            text
        }
        return Message.tool(Contents.of(Content.ToolResponse(name, response)))
    }

    /** A model's tool calls as OpenAI tool calls with fresh ids and arguments encoded as a JSON string. */
    fun toOpenAiToolCalls(calls: List<ToolCall>): List<ToolCallDto> =
        calls.mapIndexed { index, call ->
            ToolCallDto(
                index = index,
                id = "call_" + UUID.randomUUID().toString().replace("-", ""),
                type = "function",
                function = FunctionCallDto(call.name, gson.toJson(call.arguments))
            )
        }

    /** `text` or absent: none; `json_object`: any JSON; `json_schema`: constrained to the schema. */
    fun responseFormat(dto: ResponseFormatDto?): ResponseFormat? {
        if (dto == null) return null
        return when (dto.type) {
            null, "text" -> null
            "json_object" -> ResponseFormat.json("{}")
            "json_schema" -> {
                val schema = dto.json_schema?.schema
                require(schema != null && schema.isJsonObject) { "response_format.json_schema.schema must be a JSON object" }
                val text = gson.toJson(schema)
                require(text.length <= MAX_SCHEMA_CHARS) { "response_format schema is too large" }
                try {
                    ResponseFormat.json(text)
                } catch (e: IllegalArgumentException) {
                    // The SDK's message can echo the schema; the client gets a fixed one.
                    throw IllegalArgumentException("response_format schema is not supported")
                }
            }
            else -> throw IllegalArgumentException("response_format.type must be text, json_object or json_schema")
        }
    }

    /** `none` turns thinking off, the other efforts turn it on without a budget, absent keeps the model's default. */
    fun thinkingConfig(effort: String?): ThinkingConfig? = when {
        effort == null -> null
        effort == "none" -> ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)
        effort in THINKING_EFFORTS -> ThinkingConfig(enableThinking = true, thinkingTokenBudget = -1)
        else -> throw IllegalArgumentException("reasoning_effort must be none, minimal, low, medium, high or xhigh")
    }

    private fun toKotlin(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonPrimitive -> element.asJsonPrimitive.let {
            when {
                it.isBoolean -> it.asBoolean
                it.isNumber -> it.asString.toLongOrNull() ?: it.asDouble // whole numbers stay whole
                else -> it.asString
            }
        }
        element.isJsonArray -> element.asJsonArray.map { toKotlin(it) }
        else -> element.asJsonObject.entrySet().associate { it.key to toKotlin(it.value) }
    }
}
