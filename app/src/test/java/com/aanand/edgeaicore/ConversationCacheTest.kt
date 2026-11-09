package com.aanand.edgeaicore

import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationCacheTest {

    private val settings = CacheSettings(0.8, 0.95, 40, null)
    private fun user(text: String) = ChatMessage("user", JsonPrimitive(text))
    private fun assistant(text: String) = ChatMessage("assistant", JsonPrimitive(text))

    private class Fake(val name: String) { var closed = 0 }

    private var now = 0L
    private fun cache(max: Int = 2, ttl: Long = 1_000) = ConversationCache<Fake>(max, ttl, { now }) { it.closed++ }

    @Test
    fun anExactExtensionHitsAndTheEntryIsHandedOut() {
        val cache = cache()
        val a = Fake("a")
        val history = listOf(user("1"), assistant("r1"))
        cache.put(settings, history, a)
        assertSame(a, cache.take(settings, history))
        assertEquals(0, cache.size) // taken: no longer cached while it generates
        assertNull(cache.take(settings, history))
    }

    @Test
    fun changedHistorySettingsReplyOrMultimodalPartsMiss() {
        val cache = cache()
        val history = listOf(user("1"), assistant("r1"))
        cache.put(settings, history, Fake("a"))
        assertNull(cache.take(settings, listOf(user("1 edited"), assistant("r1"))))
        assertNull(cache.take(settings, listOf(user("1"), assistant("r1 trimmed"))))
        assertNull(cache.take(settings.copy(temperature = 0.1), history))
        assertNull(cache.take(settings.copy(systemInstruction = "be brief"), history))
        val image = com.google.gson.JsonArray().apply {
            add(com.google.gson.JsonObject().apply { addProperty("type", "image_url") })
        }
        assertNull(cache.take(settings, listOf(ChatMessage("user", image), assistant("r1"))))
        assertEquals(1, cache.size)
    }

    @Test
    fun theLeastRecentlyUsedEntryIsClosedBeyondTheCap() {
        val cache = cache(max = 2)
        val a = Fake("a"); val b = Fake("b"); val c = Fake("c")
        cache.put(settings, listOf(user("a")), a)
        now = 1; cache.put(settings, listOf(user("b")), b)
        now = 2; cache.put(settings, listOf(user("c")), c)
        assertEquals(1, a.closed)
        assertEquals(0, b.closed)
        assertEquals(2, cache.size)
    }

    @Test
    fun idleEntriesAreClosedLazilyOnTheNextCall() {
        val cache = cache(ttl = 1_000)
        val a = Fake("a")
        cache.put(settings, listOf(user("a")), a)
        now = 1_001
        assertEquals(0, a.closed) // lazy: nothing happens until a call
        assertNull(cache.take(settings, listOf(user("a"))))
        assertEquals(1, a.closed)
        assertEquals(0, cache.size)
    }

    @Test
    fun clearClosesEveryCachedEntryButNotATakenOne() {
        val cache = cache()
        val a = Fake("a"); val b = Fake("b")
        cache.put(settings, listOf(user("a")), a)
        cache.put(settings, listOf(user("b")), b)
        val taken = cache.take(settings, listOf(user("a")))
        cache.clear()
        assertEquals(0, taken!!.closed)
        assertEquals(1, b.closed)
        assertEquals(0, cache.size)
    }

    @Test
    fun toolsThinkingAndResponseFormatAreSettingsToo() {
        val cache = cache()
        val history = listOf(user("1"), assistant("r1"))
        cache.put(settings, history, Fake("a"))
        assertNull(cache.take(settings.copy(toolsKey = "[{\"name\":\"f\"}]"), history))
        assertNull(cache.take(settings.copy(thinking = com.google.ai.edge.litertlm.ThinkingConfig(true, -1)), history))
        assertNull(cache.take(settings.copy(responseFormatEnabled = true), history))
        assertEquals(1, cache.size)
    }
}
