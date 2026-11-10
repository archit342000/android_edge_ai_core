package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolProvider

/**
 * What a chat request asks of the conversation beyond the messages: tools, a response format and thinking.
 * [toolsKey] and [responseFormatEnabled] join the conversation-reuse settings.
 */
data class ChatOptions(
    val tools: List<ToolProvider> = emptyList(),
    val toolsKey: String? = null,
    val thinking: ThinkingConfig? = null,
    val responseFormat: ResponseFormat? = null
) {
    val responseFormatEnabled: Boolean get() = responseFormat != null
}
