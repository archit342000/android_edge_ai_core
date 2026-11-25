package com.aanand.edgeaicore

/** A request waited longer than allowed for the engine, or the server holds too many requests. Fixed, client-safe message. */
class EngineBusyException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE = "Server busy"
    }
}

/** One generation ran longer than allowed and was stopped. Fixed, client-safe message. */
class GenerationTimeoutException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE = "Generation timed out"
    }
}

/** No engine is loaded, so there is nothing to generate with. Fixed, client-safe message. */
class ModelNotLoadedException : IllegalStateException(MESSAGE) {
    companion object {
        const val MESSAGE = "Model not loaded"
    }
}
