package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a client conversation with its associated LiteRT-LM conversation.
 */
data class ConversationState(
    val conversationId: String,
    val apiToken: String,
    val systemInstruction: String? = null,
    val history: MutableList<ChatMessage> = mutableListOf(),
    var engineConversation: Conversation?,
    val ttlMs: Long,
    var lastAccessTime: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    var temperature: Double = 0.8,
    var topP: Double = 0.95,
    var topK: Int = 40
) {
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastAccessTime > ttlMs
    }

    fun touch() {
        lastAccessTime = System.currentTimeMillis()
    }
}

/**
 * Manages client conversations with TTL (Time-To-Live) support.
 */
class ConversationManager(
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
    private val cleanupIntervalMs: Long = CLEANUP_INTERVAL_MS
) {
    private val conversations = ConcurrentHashMap<String, ConversationState>()
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        startCleanupTask()
    }

    fun createConversation(apiToken: String, systemInstruction: String? = null, ttlMs: Long = defaultTtlMs): ConversationState {
        val conversationId = UUID.randomUUID().toString()
        val state = ConversationState(
            conversationId = conversationId,
            apiToken = apiToken,
            systemInstruction = systemInstruction,
            engineConversation = null,
            ttlMs = ttlMs
        )
        conversations[conversationId] = state
        Log.i(TAG, "Created conversation: ${conversationId.take(8)}... for token: ${apiToken.take(8)}... SystemInstruction: ${systemInstruction?.take(20) ?: "None"}")
        return state
    }

    fun getConversation(conversationId: String, apiToken: String): ConversationState? {
        val state = conversations[conversationId]
        if (state == null) {
            Log.w(TAG, "Conversation not found: ${conversationId.take(8)}...")
            return null
        }
        if (state.apiToken != apiToken) {
            Log.w(TAG, "Conversation ${conversationId.take(8)}... unauthorized access attempt by token ${apiToken.take(8)}...")
            return null
        }
        if (state.isExpired()) {
            Log.w(TAG, "Conversation expired: ${conversationId.take(8)}...")
            closeConversation(conversationId, apiToken)
            return null
        }
        state.touch()
        return state
    }

    fun closeConversation(conversationId: String, apiToken: String): Boolean {
        val state = conversations[conversationId]
        if (state == null || state.apiToken != apiToken) return false
        return removeConversation(conversationId)
    }

    fun closeAllForToken(apiToken: String): Int {
        val toClose = conversations.values.filter { it.apiToken == apiToken }.map { it.conversationId }
        toClose.forEach { removeConversation(it) }
        return toClose.size
    }

    private fun removeConversation(conversationId: String): Boolean {
        val state = conversations.remove(conversationId)
        if (state != null) {
            try {
                state.engineConversation?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing engine conversation", e)
            }
            Log.i(TAG, "Removed conversation: ${conversationId.take(8)}...")
            return true
        }
        return false
    }

    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                val expired = conversations.filter { it.value.isExpired() }.keys
                expired.forEach { removeConversation(it) }
            }
        }
    }

    fun shutdown() {
        cleanupJob?.cancel()
        conversations.keys.toList().forEach { removeConversation(it) }
    }

    companion object {
        private const val TAG = "ConversationManager"
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L // 30 mins
        const val CLEANUP_INTERVAL_MS = 60 * 1000L // 1 min
    }
}
