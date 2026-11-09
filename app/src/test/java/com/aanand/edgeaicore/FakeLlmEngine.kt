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
    /** When true the reply never ends until [cancelProcess], which then ends it with a cancellation error like the native engine. */
    private val hangUntilCancelled: Boolean = false,
    /** Runs at the end of [sendMessageAsync], to simulate a cancel that lands right after the send. */
    private val onSend: (() -> Unit)? = null
) : LlmConversation {
    @Volatile var closed = false
    @Volatile var sent = 0
    @Volatile private var pending: MessageCallback? = null
    override val isAlive: Boolean get() = !closed

    override fun sendMessageAsync(message: Message, callback: MessageCallback) {
        if (hangUntilCancelled) {
            pending = callback
            sent++
            onSend?.invoke()
            return
        }
        sent++
        if (failure != null) {
            callback.onError(failure)
            return
        }
        chunks.forEach { callback.onMessage(Message.model(Contents.of(Content.Text(it)))) }
        callback.onDone()
    }

    var cancelCount = 0

    override fun cancelProcess() {
        cancelCount++
        pending?.onError(kotlinx.coroutines.CancellationException("cancelled"))
        pending = null
    }

    override fun close() {
        closed = true
    }
}

class FakeEngine(
    private val chunks: List<String>,
    private val failure: Throwable?,
    /** Conversations created while this is true hang until cancelled (see [FakeConversation]). */
    @Volatile var hangUntilCancelled: Boolean = false
) : LlmEngine {
    /** Runs inside [createConversation], to simulate a cancel while the conversation is being built. */
    @Volatile var onCreateConversation: (() -> Unit)? = null
    /** Passed to conversations created from now on (see [FakeConversation]). */
    @Volatile var onSendMessage: (() -> Unit)? = null
    val conversations = java.util.concurrent.CopyOnWriteArrayList<FakeConversation>()
    var closed = false

    override fun createConversation(config: ConversationConfig): LlmConversation {
        onCreateConversation?.invoke()
        return FakeConversation(chunks, failure, hangUntilCancelled, onSendMessage).also { conversations.add(it) }
    }

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
        engine = FakeEngine(chunks, failure, hangUntilCancelled)
        return engine
    }
}
