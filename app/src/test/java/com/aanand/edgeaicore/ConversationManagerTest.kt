package com.aanand.edgeaicore

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationManagerTest {
    @Test
    fun aPersistedStateWithoutSeedStillLoadsAndKeepsSeedZero() {
        // Compatibility check: the format ConversationManager wrote before `seed` existed still parses
        val json = """{"conversationId":"c1","apiToken":"t","history":[],"ttlMs":1000,"lastAccessTime":1,"createdAt":1,"temperature":0.8,"topP":0.95,"topK":40}"""
        val state = Gson().fromJson(json, ConversationState::class.java)
        assertEquals(0, state.seed)
        assertEquals(40, state.topK)
    }

    @Test
    fun seedRoundTripsThroughGson() {
        val state = ConversationState(conversationId = "c1", apiToken = "t", ttlMs = 1000, seed = 42)
        assertEquals(42, Gson().fromJson(Gson().toJson(state), ConversationState::class.java).seed)
    }
}
