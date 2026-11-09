package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback

// Shared fakes for AiEngineManagerTest and OpenAIServerTest: no native library or model needed.

/** Fake conversation: replies with [chunks], or fails with [failure]. */
class FakeConversation(
    private val chunks: List<String>,
    private val failure: Throwable?,
    /** When true a message is never answered (the callback is not called), like a long generation. */
    private val hang: Boolean = false
) : LlmConversation {
    @Volatile var closed = false
    @Volatile var sent = 0
    override val isAlive: Boolean get() = !closed

    override fun sendMessageAsync(message: Message, callback: MessageCallback) {
        sent++
        if (hang) return
        if (failure != null) {
            callback.onError(failure)
            return
        }
        chunks.forEach { callback.onMessage(Message.model(Contents.of(Content.Text(it)))) }
        callback.onDone()
    }

    override fun close() {
        closed = true
    }
}

class FakeEngine(
    private val chunks: List<String>,
    private val failure: Throwable?
) : LlmEngine {
    val conversations = java.util.concurrent.CopyOnWriteArrayList<FakeConversation>()
    /** Conversations created while this is true never answer (see [FakeConversation]). */
    @Volatile var hangUntilCancelled = false
    var closed = false

    override fun createConversation(config: ConversationConfig): LlmConversation =
        FakeConversation(chunks, failure, hangUntilCancelled).also { conversations.add(it) }

    override fun close() {
        closed = true
    }
}

class FakeEngineFactory(
    private val chunks: List<String> = listOf("Hel", "lo"),
    private val failure: Throwable? = null,
    private val failOnGpu: Boolean = false,
    private val hangUntilCancelled: Boolean = false
) : (EngineConfig) -> LlmEngine {
    val configs = mutableListOf<EngineConfig>()
    lateinit var engine: FakeEngine

    override fun invoke(config: EngineConfig): LlmEngine {
        configs.add(config)
        if (failOnGpu && config.backend is Backend.GPU) {
            throw IllegalStateException("GPU unavailable")
        }
        engine = FakeEngine(chunks, failure).also { it.hangUntilCancelled = hangUntilCancelled }
        return engine
    }
}
