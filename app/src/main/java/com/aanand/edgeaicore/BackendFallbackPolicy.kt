package com.aanand.edgeaicore

/** Decides which backend to retry on when the requested one cannot be used. */
object BackendFallbackPolicy {
    const val CPU = "CPU"
    const val GPU = "GPU"

    /**
     * The backend to retry on after [requested] failed to initialise or to answer its first request, or null when
     * the failure should be reported instead. Only GPU falls back (to CPU); CPU and NPU failures are reported.
     */
    fun next(requested: String): String? = if (requested.equals(GPU, ignoreCase = true)) CPU else null
}
