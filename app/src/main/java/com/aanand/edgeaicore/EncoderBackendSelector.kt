package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.BackendType

/** The backend chosen for one encoder; [backend] is null when the model has no usable encoder. */
data class EncoderSelection(val backend: BackendType?, val overrideIgnored: Boolean = false)

/** Picks the backend an encoder runs on. Pure; the caller logs and reports an ignored override. */
object EncoderBackendSelector {
    private val USABLE = setOf(BackendType.CPU, BackendType.GPU)

    /**
     * @param supported the backends the encoder supports (CPU and GPU only), or null when the model has no usable encoder
     * @param mainBackend the main backend name ("CPU", "GPU" or "NPU"); an NPU main backend counts as CPU here
     * @param override the user's choice
     */
    fun select(supported: List<BackendType>?, mainBackend: String, override: EncoderBackendOption): EncoderSelection {
        if (supported == null) return EncoderSelection(null)
        val wanted = when (override) {
            EncoderBackendOption.CPU -> BackendType.CPU
            EncoderBackendOption.GPU -> BackendType.GPU
            EncoderBackendOption.AUTO -> null
        }
        if (wanted != null && wanted in supported) return EncoderSelection(wanted)
        val main = if (mainBackend.equals("GPU", ignoreCase = true)) BackendType.GPU else BackendType.CPU
        val automatic = when {
            main in supported -> main
            BackendType.CPU in supported -> BackendType.CPU
            else -> BackendType.GPU
        }
        return EncoderSelection(automatic, overrideIgnored = wanted != null)
    }

    /** Keeps the CPU and GPU entries; an empty list from the model means "no constraint", so CPU and GPU. NPU-only gives null. */
    fun usableBackends(present: Boolean, fromModel: List<BackendType>): List<BackendType>? {
        if (!present) return null
        val listed = if (fromModel.isEmpty()) listOf(BackendType.CPU, BackendType.GPU) else fromModel
        return listed.filter { it in USABLE }.distinct().takeIf { it.isNotEmpty() }
    }
}

/** The encoders a loaded engine was created with; a null backend means the model has no usable encoder of that kind. */
data class EncoderSummary(
    val vision: BackendType? = null,
    val audio: BackendType? = null,
    val visionOverrideIgnored: Boolean = false,
    val audioOverrideIgnored: Boolean = false
) {
    val supportsImages: Boolean get() = vision != null
    val supportsAudio: Boolean get() = audio != null

    /** Short text for the service status, for example `vision CPU, audio CPU`. */
    fun describe(): String {
        fun part(name: String, backend: BackendType?, ignored: Boolean) =
            "$name ${backend?.name ?: "off"}" + if (ignored) " (override ignored)" else ""
        return part("vision", vision, visionOverrideIgnored) + ", " + part("audio", audio, audioOverrideIgnored)
    }
}
