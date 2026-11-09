package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ModelType

/** What kind of model a `.litertlm` file holds. */
enum class ModelKind { LLM, EMBEDDING, UNKNOWN }

/** The values [ModelInspector] copies out of LiteRT-LM's `ModelInfo`; plain data so tests need no native code. */
data class RawModelInfo(
    val kind: ModelKind,
    val text: Boolean,
    val vision: Boolean,
    val audio: Boolean,
    val speculativeSupport: Boolean = false,
    /** The backends the model lists for its encoders; empty when the model records none. */
    val visionBackends: List<BackendType> = emptyList(),
    val audioBackends: List<BackendType> = emptyList(),
    val supportsTools: Boolean = false,
    val supportsThinking: Boolean = false,
    /** The model's maximum context in tokens; 0 when it does not say. */
    val maxContextTokens: Int = 0,
    val dynamicContext: Boolean = false
)

/** What the app needs to know about a model before it loads it. */
data class ModelCapabilities(
    val kind: ModelKind,
    val supportsText: Boolean,
    val supportsVision: Boolean,
    val supportsAudio: Boolean,
    /** True for an LLM that ships a speculative decoding (MTP) drafter. */
    val speculativeDecoding: Boolean = false,
    /** CPU/GPU backends the vision encoder can run on; null when the model has no (usable) vision encoder. */
    val visionBackends: List<BackendType>? = null,
    val audioBackends: List<BackendType>? = null,
    /** The model supports function calling (tools). */
    val supportsTools: Boolean = false,
    /** The model supports thinking (reasoning_effort). */
    val supportsThinking: Boolean = false,
    /** The model's maximum context in tokens; 0 when it does not say. */
    val maxContextTokens: Int = 0,
    /** True when the context size is dynamic, so [maxContextTokens] is not a hard limit. */
    val dynamicContext: Boolean = false
)

/** The model file cannot be served by this app (corrupt, not an LLM, or without text input). */
class UnsupportedModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads a model file's metadata with LiteRT-LM `ModelInfo` before the engine is created, so an unsupported file is
 * rejected with an exception instead of aborting the process natively. Blocking native I/O: call on `Dispatchers.IO`.
 *
 * @param reader reads the raw metadata; the default is the only code that touches LiteRT-LM.
 */
class ModelInspector(private val reader: (String) -> RawModelInfo = ::readModelInfo) {

    /** @throws UnsupportedModelException if LiteRT-LM cannot read the file's metadata. */
    fun inspect(modelPath: String): ModelCapabilities {
        val raw = try {
            reader(modelPath)
        } catch (e: LiteRtLmJniException) {
            Log.w(TAG, "Model metadata could not be read: ${e.message}")
            throw UnsupportedModelException("Model file could not be read", e)
        }
        return toCapabilities(raw)
    }

    companion object {
        private const val TAG = "ModelInspector"

        fun toCapabilities(raw: RawModelInfo) =
            ModelCapabilities(
                kind = raw.kind, supportsText = raw.text, supportsVision = raw.vision, supportsAudio = raw.audio,
                speculativeDecoding = raw.kind == ModelKind.LLM && raw.speculativeSupport,
                visionBackends = EncoderBackendSelector.usableBackends(raw.vision, raw.visionBackends),
                audioBackends = EncoderBackendSelector.usableBackends(raw.audio, raw.audioBackends),
                supportsTools = raw.kind == ModelKind.LLM && raw.supportsTools,
                supportsThinking = raw.kind == ModelKind.LLM && raw.supportsThinking,
                maxContextTokens = raw.maxContextTokens,
                dynamicContext = raw.dynamicContext
            )

        /** @throws UnsupportedModelException unless the model is an LLM that accepts text. */
        fun validate(capabilities: ModelCapabilities) {
            if (capabilities.kind != ModelKind.LLM) {
                throw UnsupportedModelException("Model is not a language model (${capabilities.kind})")
            }
            if (!capabilities.supportsText) {
                throw UnsupportedModelException("Model does not accept text input")
            }
        }
    }
}

/** Opens the file with LiteRT-LM and copies out the metadata the app needs. */
internal fun readModelInfo(modelPath: String): RawModelInfo =
    ModelInfo.from(modelPath).use { info ->
        val modalities = info.inputModalities()
        val kind = when (info.modelType) {
            ModelType.LLM -> ModelKind.LLM
            ModelType.EMBEDDING -> ModelKind.EMBEDDING
            ModelType.UNKNOWN -> ModelKind.UNKNOWN
        }
        Log.d("ModelInspector", "Model info: type=${info.modelType} tools=${(info as? ModelInfo.Llm)?.supportsFunctionCalling()} thinking=${(info as? ModelInfo.Llm)?.supportsThinking()} maxContext=${info.maxContextTokens()} dynamic=${info.isDynamicContext()}")
        RawModelInfo(
            kind, modalities.text, modalities.vision, modalities.audio,
            speculativeSupport = info is ModelInfo.Llm && info.hasSpeculativeDecodingSupport(),
            visionBackends = if (modalities.vision) info.supportedBackends(Modality.VISION) else emptyList(),
            audioBackends = if (modalities.audio) info.supportedBackends(Modality.AUDIO) else emptyList(),
            supportsTools = info is ModelInfo.Llm && info.supportsFunctionCalling(),
            supportsThinking = info is ModelInfo.Llm && info.supportsThinking(),
            maxContextTokens = info.maxContextTokens(),
            dynamicContext = info.isDynamicContext()
        )
    }
