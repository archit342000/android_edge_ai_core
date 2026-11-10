package com.aanand.edgeaicore

import com.google.gson.JsonElement

data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val top_k: Int? = null,
    val stream: Boolean? = false,
    val stream_options: StreamOptions? = null,
    val max_tokens: Int? = null,
    val max_completion_tokens: Int? = null,
    val presence_penalty: Double? = null,
    val frequency_penalty: Double? = null,
    val seed: Int? = null,
    val tools: List<ToolDto>? = null,
    val tool_choice: JsonElement? = null,
    val response_format: ResponseFormatDto? = null,
    val reasoning_effort: String? = null
) {
    /** The output cap: `max_completion_tokens` (OpenAI's newer name) wins over `max_tokens`. */
    val maxOutputTokens: Int? get() = max_completion_tokens ?: max_tokens
}

/** The `stream_options` request field; only `include_usage` is supported. */
data class StreamOptions(
    val include_usage: Boolean? = null
)

/**
 * One chat message. `content` is null for an assistant turn that only carries `tool_calls`; a `tool` message answers
 * the call with the same `tool_call_id`.
 */
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    val tool_calls: List<ToolCallDto>? = null,
    val tool_call_id: String? = null,
    val name: String? = null
)

/** An OpenAI tool call; `index` is only set in streaming chunks. */
data class ToolCallDto(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = "function",
    val function: FunctionCallDto? = null
)

/** `arguments` is a JSON object encoded as a string, as OpenAI sends it. */
data class FunctionCallDto(val name: String? = null, val arguments: String? = null)

/** A tool the client offers: only `type: "function"` exists. */
data class ToolDto(val type: String? = "function", val function: ToolFunctionDto? = null)

data class ToolFunctionDto(val name: String? = null, val description: String? = null, val parameters: JsonElement? = null)

/** `type` is `text`, `json_object` or `json_schema`. */
data class ResponseFormatDto(val type: String? = null, val json_schema: JsonSchemaDto? = null)

data class JsonSchemaDto(val name: String? = null, val schema: JsonElement? = null)

data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage
)

data class Choice(
    val index: Int,
    val message: ChatMessageResponse,
    val finish_reason: String
)

data class ChatMessageResponse(
    val role: String,
    val content: String?,
    val tool_calls: List<ToolCallDto>? = null,
    val reasoning_content: String? = null
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
    val prompt_tokens_details: PromptTokensDetails? = null
)

data class PromptTokensDetails(
    val cached_tokens: Int? = null
)

/**
 * Token counts of one generation, as the official LiteRT-LM server computes them: [completionTokens] is the number of
 * decoded tokens, [promptTokens] is the prefilled tokens plus the [cachedTokens] already in the KV cache.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    fun toUsage() = Usage(
        prompt_tokens = promptTokens,
        completion_tokens = completionTokens,
        total_tokens = totalTokens,
        prompt_tokens_details = PromptTokensDetails(cached_tokens = cachedTokens)
    )

    companion object {
        /**
         * @param prefillTokens tokens prefilled for this generation (`BenchmarkInfo.lastPrefillTokenCount`)
         * @param decodeTokens tokens decoded (`BenchmarkInfo.lastDecodeTokenCount`)
         * @param kvTokenCount tokens now in the conversation's KV cache (`Conversation.getTokenCount()`)
         */
        fun from(prefillTokens: Int, decodeTokens: Int, kvTokenCount: Int): TokenUsage {
            val cached = maxOf(0, kvTokenCount - prefillTokens - decodeTokens)
            return TokenUsage(promptTokens = cached + prefillTokens, completionTokens = decodeTokens, cachedTokens = cached)
        }
    }
}

/** The engine finished a generation but its token counts could not be read. */
class UsageUnavailableException(cause: Throwable) : Exception(MESSAGE, cause) {
    companion object {
        /** The only text a client sees for this failure; never the cause's message. */
        const val MESSAGE = "Token usage unavailable"
    }
}

// ===========================
// Conversation Management Models
// ===========================

/**
 * Response for conversation creation
 */
data class ConversationResponse(
    val conversation_id: String,
    val ttl_ms: Long,
    val created_at: Long,
    val expires_at: Long
)

/**
 * Response for conversation info query
 */
data class ConversationInfoResponse(
    val conversation_id: String,
    val ttl_ms: Long,
    val created_at: Long,
    val last_access_time: Long,
    val expires_at: Long,
    val remaining_ttl_ms: Long
)

/**
 * Generic success response
 */
data class SuccessResponse(
    val success: Boolean
)

/**
 * Body of the health routes: `backend` is the backend the engine actually runs on.
 */
data class HealthResponse(
    val status: String,
    val backend: String?
)

/**
 * Generic error response
 */
data class ErrorResponse(
    val error: String
)
