package com.aanand.edgeaicore

/** Checks the ranges of a chat request before any generation starts. */
object ChatRequestValidator {
    const val MAX_MESSAGES = 256
    const val MAX_TEMPERATURE = 2.0
    const val MAX_PENALTY = 2.0
    const val MAX_STOP_SEQUENCES = 4
    const val MAX_STOP_LENGTH = 64

    /** @return null when the request is acceptable, otherwise a fixed short message for a 400 response */
    fun validate(request: ChatCompletionRequest): String? {
        if (request.messages.isEmpty() || request.messages.size > MAX_MESSAGES) {
            return "messages must contain 1 to $MAX_MESSAGES items"
        }
        if (stopSequences(request.stop) == null) return "stop must be a string or up to $MAX_STOP_SEQUENCES strings of 1 to $MAX_STOP_LENGTH characters"
        request.n?.let { if (it != 1) return "n must be 1" }
        request.temperature?.let {
            if (!inRange(it, 0.0, MAX_TEMPERATURE)) return "temperature must be between 0 and $MAX_TEMPERATURE"
        }
        request.top_p?.let { if (it.isNaN() || it <= 0 || it > 1) return "top_p must be greater than 0 and at most 1" }
        request.top_k?.let { if (it < 1) return "top_k must be at least 1" }
        request.presence_penalty?.let {
            if (!inRange(it, -MAX_PENALTY, MAX_PENALTY)) return "presence_penalty must be between -$MAX_PENALTY and $MAX_PENALTY"
        }
        request.frequency_penalty?.let {
            if (!inRange(it, -MAX_PENALTY, MAX_PENALTY)) return "frequency_penalty must be between -$MAX_PENALTY and $MAX_PENALTY"
        }
        request.max_tokens?.let { if (it < 1) return "max_tokens must be at least 1" }
        request.max_completion_tokens?.let { if (it < 1) return "max_completion_tokens must be at least 1" }
        return null
    }

    private fun inRange(value: Double, min: Double, max: Double) = !value.isNaN() && value >= min && value <= max

    /**
     * The stop sequences of a `stop` field: empty when absent or JSON null, the list otherwise, or null when the value
     * is invalid (not a string or a list of 1 to [MAX_STOP_SEQUENCES] non-empty strings of at most [MAX_STOP_LENGTH]).
     */
    fun stopSequences(stop: com.google.gson.JsonElement?): List<String>? {
        if (stop == null || stop.isJsonNull) return emptyList()
        val values: List<com.google.gson.JsonElement> = when {
            stop.isJsonPrimitive -> listOf(stop)
            stop.isJsonArray -> stop.asJsonArray.toList()
            else -> return null
        }
        if (values.isEmpty() || values.size > MAX_STOP_SEQUENCES) return null
        val result = values.map { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
            element.asString.also { if (it.isEmpty() || it.length > MAX_STOP_LENGTH) return null }
        }
        return result
    }
}
