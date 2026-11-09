package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback

// Shared fakes for AiEngineManagerTest and OpenAIServerTest: no native library or model needed.

/** Fake conversation: replies with [chunks], or fails with [failure]. */
class FakeConversation(
    private val chunks: List<String>,
    private val failure: Throwable?,
    /** Counts the fake reports after a generation: prefilled tokens, decoded tokens, tokens in the KV cache. */
    private val counts: Triple<Int, Int, Int> = Triple(7, 3, 10),
    /** When set, reading the token counts throws it. */
    private val usageFailure: Throwable? = null
) : LlmConversation {
    var closed = false
    var sent = 0

    override fun getTokenCount(): Int {
        usageFailure?.let { throw it }
        return counts.third
    }

    override fun getBenchmarkInfo(): BenchmarkInfo {
        usageFailure?.let { throw it }
        return BenchmarkInfo(
            initTimeInSecond = 0.0,
            timeToFirstTokenInSecond = 0.0,
            lastPrefillTokenCount = counts.first,
            lastDecodeTokenCount = counts.second,
            lastPrefillTokensPerSecond = 0.0,
            lastDecodeTokensPerSecond = 0.0
        )
    }

    override val isAlive: Boolean get() = !closed

    override fun sendMessageAsync(message: Message, callback: MessageCallback) {
        sent++
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
    private val failure: Throwable?,
    private val counts: Triple<Int, Int, Int> = Triple(7, 3, 10),
    private val usageFailure: Throwable? = null
) : LlmEngine {
    val conversations = mutableListOf<FakeConversation>()
    var closed = false

    override fun createConversation(config: ConversationConfig): LlmConversation =
        FakeConversation(chunks, failure, counts, usageFailure).also { conversations.add(it) }

    override fun close() {
        closed = true
    }
}

class FakeEngineFactory(
    private val chunks: List<String> = listOf("Hel", "lo"),
    private val failure: Throwable? = null,
    private val failOnGpu: Boolean = false,
    private val counts: Triple<Int, Int, Int> = Triple(7, 3, 10),
    private val usageFailure: Throwable? = null
) : (EngineConfig) -> LlmEngine {
    val configs = mutableListOf<EngineConfig>()
    /** `ExperimentalFlags.enableBenchmark` as it was when each engine was created (the flag is read at creation). */
    val benchmarkFlagAtCreation = mutableListOf<Boolean>()
    lateinit var engine: FakeEngine

    override fun invoke(config: EngineConfig): LlmEngine {
        configs.add(config)
        @OptIn(ExperimentalApi::class)
        benchmarkFlagAtCreation.add(ExperimentalFlags.enableBenchmark)
        if (failOnGpu && config.backend is Backend.GPU) {
            throw IllegalStateException("GPU unavailable")
        }
        engine = FakeEngine(chunks, failure, counts, usageFailure)
        return engine
    }
}

/** A model every test accepts: an LLM with text, vision and audio input. */
val FULL_LLM = ModelCapabilities(ModelKind.LLM, supportsText = true, supportsVision = true, supportsAudio = true)

/** An [AiEngineManager] whose model inspection is faked, so tests need no native library or model file. */
fun newManager(
    engineFactory: (EngineConfig) -> LlmEngine,
    capabilities: (String) -> ModelCapabilities = { FULL_LLM }
): AiEngineManager = AiEngineManager(engineFactory).also { it.modelInspector = capabilities }
