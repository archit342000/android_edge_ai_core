package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall

// Shared fakes for AiEngineManagerTest and OpenAIServerTest: no native library or model needed.

/** Fake conversation: replies with [chunks], or fails with [failure]. */
class FakeConversation(
    private val chunks: List<String>,
    private val failure: Throwable?,
    /** When true a message is never answered (the callback is not called), like a long generation. */
    private val hang: Boolean = false,
    /** Tool calls the fake model returns with its reply. */
    private val toolCalls: List<ToolCall> = emptyList(),
    /** Thinking text the fake model returns on the `thought` channel before its reply. */
    private val thinking: String? = null
) : LlmConversation {
    @Volatile var closed = false
    @Volatile var sent = 0
    /** The per-request options and the message of the last send. */
    var lastResponseFormat: ResponseFormat? = null
    var lastThinking: ThinkingConfig? = null
    var lastMessage: Message? = null
    override val isAlive: Boolean get() = !closed

    override fun sendMessageAsync(
        message: Message,
        callback: MessageCallback,
        responseFormat: ResponseFormat?,
        thinkingConfig: ThinkingConfig?
    ) {
        lastResponseFormat = responseFormat
        lastThinking = thinkingConfig
        lastMessage = message
        sent++
        if (hang) return
        if (failure != null) {
            callback.onError(failure)
            return
        }
        thinking?.let { callback.onMessage(Message.model(Contents.of(emptyList<Content>()), emptyList(), mapOf("thought" to it))) }
        chunks.forEach { callback.onMessage(Message.model(Contents.of(Content.Text(it)))) }
        if (toolCalls.isNotEmpty()) callback.onMessage(Message.model(Contents.of(emptyList<Content>()), toolCalls))
        callback.onDone()
    }

    override fun close() {
        closed = true
    }
}

class FakeEngine(
    private val chunks: List<String>,
    private val failure: Throwable?,
    private val toolCalls: List<ToolCall> = emptyList(),
    private val thinking: String? = null
) : LlmEngine {
    val conversations = java.util.concurrent.CopyOnWriteArrayList<FakeConversation>()
    /** The config of every conversation created, in order. */
    val conversationConfigs = java.util.concurrent.CopyOnWriteArrayList<ConversationConfig>()
    /** Conversations created while this is true never answer (see [FakeConversation]). */
    @Volatile var hangUntilCancelled = false
    var closed = false

    override fun createConversation(config: ConversationConfig): LlmConversation {
        conversationConfigs.add(config)
        return FakeConversation(chunks, failure, hangUntilCancelled, toolCalls, thinking).also { conversations.add(it) }
    }

    override fun close() {
        closed = true
    }
}

class FakeEngineFactory(
    private val chunks: List<String> = listOf("Hel", "lo"),
    private val failure: Throwable? = null,
    private val failOnGpu: Boolean = false,
    private val hangUntilCancelled: Boolean = false,
    private val toolCalls: List<ToolCall> = emptyList(),
    private val thinking: String? = null
) : (EngineConfig) -> LlmEngine {
    val configs = mutableListOf<EngineConfig>()
    lateinit var engine: FakeEngine

    override fun invoke(config: EngineConfig): LlmEngine {
        configs.add(config)
        if (failOnGpu && config.backend is Backend.GPU) {
            throw IllegalStateException("GPU unavailable")
        }
        engine = FakeEngine(chunks, failure, toolCalls, thinking).also { it.hangUntilCancelled = hangUntilCancelled }
        return engine
    }
}
