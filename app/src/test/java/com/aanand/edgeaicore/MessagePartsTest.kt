package com.aanand.edgeaicore

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePartsTest {
    private fun part(type: String) = JsonObject().apply { addProperty("type", type) }
    private fun message(vararg types: String) = ChatMessage("user", JsonArray().apply { types.forEach { add(part(it)) } })

    @Test
    fun imageAndAudioPartsAreFound() {
        assertTrue(MessageParts.hasImage(listOf(message("text", "image_url"))))
        assertFalse(MessageParts.hasAudio(listOf(message("text", "image_url"))))
        assertTrue(MessageParts.hasAudio(listOf(ChatMessage("user", JsonPrimitive("hi")), message("audio_url"))))
        assertFalse(MessageParts.hasImage(listOf(ChatMessage("user", JsonPrimitive("hi")), ChatMessage("assistant", JsonPrimitive("x")))))
    }

    @Test
    fun malformedContentIsNeverAMediaPart() {
        val gson = Gson()
        val noContent = gson.fromJson("""{"role":"user"}""", ChatMessage::class.java) // Gson leaves the non-null field null
        val objectContent = gson.fromJson("""{"role":"user","content":{"type":"image_url"}}""", ChatMessage::class.java)
        val objectType = gson.fromJson("""{"role":"user","content":[{"type":{}}]}""", ChatMessage::class.java)
        val noType = gson.fromJson("""{"role":"user","content":[{"image_url":{"url":"x"}}]}""", ChatMessage::class.java)
        val nonObjectPart = gson.fromJson("""{"role":"user","content":["image_url", 3]}""", ChatMessage::class.java)
        for (m in listOf(noContent, objectContent, objectType, noType, nonObjectPart)) {
            assertFalse(MessageParts.hasImage(listOf(m)))
            assertFalse(MessageParts.hasAudio(listOf(m)))
        }
    }
}
