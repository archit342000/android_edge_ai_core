package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ThinkingConfig

/**
 * The part of a LiteRT-LM `Engine` that [AiEngineManager] uses.
 * Exists so the manager can be tested with a fake engine instead of a native model.
 */
interface LlmEngine : AutoCloseable {
    fun createConversation(config: ConversationConfig): LlmConversation
}

/**
 * The part of a LiteRT-LM `Conversation` that [AiEngineManager] uses.
 */
interface LlmConversation : AutoCloseable {
    val isAlive: Boolean
    fun sendMessageAsync(
        message: Message,
        callback: MessageCallback,
        responseFormat: ResponseFormat? = null,
        thinkingConfig: ThinkingConfig? = null
    )
}

/** Production adapter over the LiteRT-LM [Engine]; forwards every call. */
class LiteRtEngine(private val engine: Engine) : LlmEngine {
    override fun createConversation(config: ConversationConfig): LlmConversation =
        LiteRtConversation(engine.createConversation(config))

    override fun close() = engine.close()
}

/** Production adapter over the LiteRT-LM [Conversation]; forwards every call. */
class LiteRtConversation(private val conversation: Conversation) : LlmConversation {
    override val isAlive: Boolean get() = conversation.isAlive

    override fun sendMessageAsync(
        message: Message,
        callback: MessageCallback,
        responseFormat: ResponseFormat?,
        thinkingConfig: ThinkingConfig?
    ) = conversation.sendMessageAsync(message, callback, responseFormat = responseFormat, thinkingConfig = thinkingConfig)

    override fun close() = conversation.close()
}

/** Default engine factory: builds and initialises a real LiteRT-LM engine (loads the native library and model). */
fun createLiteRtEngine(config: EngineConfig): LlmEngine {
    val engine = Engine(config)
    engine.initialize()
    return LiteRtEngine(engine)
}
