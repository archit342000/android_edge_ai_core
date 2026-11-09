package com.aanand.edgeaicore

import com.google.gson.JsonElement

/**
 * Separates a request's system instructions from its conversation turns.
 *
 * Every `system` (and OpenAI's `developer`) message, wherever it appears, is merged in order into one system
 * instruction, because chat templates render a single system block. The other messages keep their order.
 */
object SystemPromptSplitter {
    private val SYSTEM_ROLES = setOf("system", "developer")
    private const val SEPARATOR = "\n\n"

    /** @return the merged system instruction (null when there is none or it is blank) and the remaining messages */
    fun split(messages: List<ChatMessage>): Pair<String?, List<ChatMessage>> {
        val systemTexts = mutableListOf<String>()
        val turns = mutableListOf<ChatMessage>()
        for (message in messages) {
            if (message.role.lowercase() in SYSTEM_ROLES) {
                val text = textOf(message.content)
                if (text.isNotBlank()) systemTexts.add(text)
            } else {
                turns.add(message)
            }
        }
        return Pair(systemTexts.joinToString(SEPARATOR).ifBlank { null }, turns)
    }

    /** The text of a string content, or the joined `text` parts of an array content. */
    private fun textOf(content: JsonElement): String = when {
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray
            .filter { it.isJsonObject && (it.asJsonObject.get("type")?.asString ?: "text") == "text" }
            .mapNotNull { it.asJsonObject.get("text")?.asString }
            .joinToString("")
        else -> ""
    }
}
