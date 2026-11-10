package com.aanand.edgeaicore

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemPromptSplitterTest {

    private fun msg(role: String, text: String) = ChatMessage(role, JsonPrimitive(text))

    @Test
    fun noSystemMessageGivesNoInstructionAndKeepsTheTurns() {
        val turns = listOf(msg("user", "hi"), msg("assistant", "hello"), msg("user", "again"))
        val (instruction, rest) = SystemPromptSplitter.split(turns)
        assertNull(instruction)
        assertEquals(turns, rest)
    }

    @Test
    fun aLeadingSystemMessageBecomesTheInstruction() {
        val (instruction, rest) = SystemPromptSplitter.split(listOf(msg("system", "Be brief."), msg("user", "hi")))
        assertEquals("Be brief.", instruction)
        assertEquals(listOf(msg("user", "hi")), rest)
    }

    @Test
    fun severalSystemAndDeveloperMessagesAreMergedInOrderEvenWhenLater() {
        val (instruction, rest) = SystemPromptSplitter.split(
            listOf(msg("system", "One."), msg("user", "hi"), msg("developer", "Two."), msg("assistant", "x"), msg("SYSTEM", "Three."))
        )
        assertEquals("One.\n\nTwo.\n\nThree.", instruction)
        assertEquals(listOf(msg("user", "hi"), msg("assistant", "x")), rest)
    }

    @Test
    fun blankSystemTextGivesNoInstruction() {
        val (instruction, rest) = SystemPromptSplitter.split(listOf(msg("system", "  \n "), msg("user", "hi")))
        assertNull(instruction)
        assertEquals(1, rest.size)
    }

    @Test
    fun arrayContentUsesItsTextParts() {
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "Part A. ") })
            add(JsonObject().apply { addProperty("type", "image_url") })
            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", "Part B.") })
        }
        val (instruction, _) = SystemPromptSplitter.split(listOf(ChatMessage("system", parts), msg("user", "hi")))
        assertEquals("Part A. Part B.", instruction)
    }

    @Test
    fun onlySystemMessagesLeaveNoTurns() {
        val (instruction, rest) = SystemPromptSplitter.split(listOf(msg("system", "Only me.")))
        assertEquals("Only me.", instruction)
        assertEquals(emptyList<ChatMessage>(), rest)
    }
}
