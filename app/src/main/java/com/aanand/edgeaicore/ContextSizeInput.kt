package com.aanand.edgeaicore

/** Reads the context size box: empty means the model's default, otherwise a whole number above 0. */
object ContextSizeInput {
    sealed interface Result

    /** Empty: use the model's own context size. */
    data object ModelDefault : Result

    /** A context size in tokens. */
    data class Tokens(val count: Int) : Result

    /** Anything else; the box shows the reason and nothing is saved or started. */
    data object Invalid : Result

    fun parse(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ModelDefault
        val n = trimmed.toIntOrNull() ?: return Invalid
        return if (n > 0) Tokens(n) else Invalid
    }

    /** The value the service and the saved setting use: 0 for the model's default. */
    fun toStored(result: Result): Int? = when (result) {
        ModelDefault -> 0
        is Tokens -> result.count
        Invalid -> null
    }
}
