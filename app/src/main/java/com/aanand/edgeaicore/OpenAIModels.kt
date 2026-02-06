package com.aanand.edgeaicore

import com.google.gson.JsonElement

data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val top_k: Int? = null,
    val stream: Boolean? = false
)

data class ChatMessage(
    val role: String,
    val content: JsonElement
)

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
    val content: String
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
