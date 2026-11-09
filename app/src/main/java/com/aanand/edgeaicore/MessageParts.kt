package com.aanand.edgeaicore

/** Which media parts a request's messages carry, to reject input a model cannot take. */
object MessageParts {
    fun hasImage(messages: List<ChatMessage>): Boolean = hasPart(messages, "image_url")
    fun hasAudio(messages: List<ChatMessage>): Boolean = hasPart(messages, "audio_url")

    private fun hasPart(messages: List<ChatMessage>, type: String): Boolean =
        messages.any { message ->
            val content = message.content
            content != null && content.isJsonArray && content.asJsonArray.any {
                it.isJsonObject && it.asJsonObject.get("type")?.takeIf { t -> t.isJsonPrimitive }?.asString == type
            }
        }
}
