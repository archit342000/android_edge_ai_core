package com.aanand.edgeaicore

import android.content.Context
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.google.gson.Gson

/**
 * Represents a client conversation with its associated LiteRT-LM conversation.
 */
data class ConversationState(
    val conversationId: String,
    val apiToken: String,
    val systemInstruction: String? = null,
    val history: MutableList<ChatMessage> = mutableListOf(),
    val ttlMs: Long,
    @field:Volatile var lastAccessTime: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    var temperature: Double = 0.8,
    var topP: Double = 0.95,
    var topK: Int = 40
) {
    fun isExpired(): Boolean {
        // Check if the conversation has exceeded its TTL since the last access
        return System.currentTimeMillis() - lastAccessTime > ttlMs
    }

    fun touch() {
        lastAccessTime = System.currentTimeMillis()
    }
}

/**
 * Manages client conversations with TTL (Time-To-Live) support and local persistence.
 */
class ConversationManager(
    private val context: Context? = null,
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
    private val cleanupIntervalMs: Long = CLEANUP_INTERVAL_MS,
    private val onConversationRemoved: ((String) -> Unit)? = null
) {
    private val conversations = ConcurrentHashMap<String, ConversationState>()
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private val gson = Gson()
    private val storageDir: File? by lazy {
        context?.let { File(it.filesDir, "conversations").apply { if (!exists()) mkdirs() } }
    }

    init {
        startCleanupTask()
        loadConversations()
    }

    private fun loadConversations() {
        val dir = storageDir ?: return
        Log.i(TAG, "Loading persisted conversations from ${dir.absolutePath}")
        
        val files = dir.listFiles { _, name -> name.endsWith(".json") }
        if (files == null) {
            Log.d(TAG, "No persisted conversations found")
            return
        }

        var loadedCount = 0
        var expiredCount = 0

        files.forEach { file ->
            try {
                val json = file.readText()
                val state = gson.fromJson(json, ConversationState::class.java)
                
                if (state.isExpired()) {
                    Log.d(TAG, "Discarding expired conversation: ${state.conversationId.take(8)}...")
                    file.delete()
                    expiredCount++
                } else {
                    conversations[state.conversationId] = state
                    loadedCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load conversation from ${file.name}", e)
                file.delete()
            }
        }
        
        Log.i(TAG, "Loaded $loadedCount conversations (discarded $expiredCount expired)")
    }

    fun saveConversation(state: ConversationState) {
        val dir = storageDir ?: return
        scope.launch(Dispatchers.IO) {
            // Serialize writes to the same conversation file to prevent corruption
            synchronized(state) {
                try {
                    val file = File(dir, "${state.conversationId}.json")
                    val json = gson.toJson(state)
                    file.writeText(json)
                    Log.d(TAG, "Persisted conversation: ${state.conversationId.take(8)}...")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist conversation: ${state.conversationId.take(8)}...", e)
                }
            }
        }
    }

    private fun deletePersistentConversation(conversationId: String) {
        val dir = storageDir ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(dir, "$conversationId.json")
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted persistent file for: ${conversationId.take(8)}...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete persistent file for: ${conversationId.take(8)}...", e)
            }
        }
    }

    fun createConversation(apiToken: String, systemInstruction: String? = null, ttlMs: Long = defaultTtlMs): ConversationState {
        val conversationId = UUID.randomUUID().toString()
        val state = ConversationState(
            conversationId = conversationId,
            apiToken = apiToken,
            systemInstruction = systemInstruction,
            ttlMs = ttlMs
        )
        conversations[conversationId] = state
        Log.i(TAG, "Created conversation: ${conversationId.take(8)}... for token: ${apiToken.take(8)}... SystemInstruction: ${systemInstruction ?: "None"}")
        saveConversation(state)
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
        // Save because lastAccessTime changed
        saveConversation(state)
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
            onConversationRemoved?.invoke(conversationId)
            deletePersistentConversation(conversationId)
            Log.i(TAG, "Removed conversation: ${conversationId.take(8)}...")
            return true
        }
        return false
    }

    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                val expiredKeys = conversations.filter { it.value.isExpired() }.keys
                if (expiredKeys.isNotEmpty()) {
                    Log.i(TAG, "Cleanup: Removing ${expiredKeys.size} expired conversations")
                    expiredKeys.forEach { removeConversation(it) }
                }
            }
        }
    }

    fun deleteAllConversations() {
        Log.i(TAG, "Executing full conversation wipe (Manual Shutdown)")
        
        // 1. Clear memory and notify engine
        conversations.keys.toList().forEach { id ->
            val state = conversations.remove(id)
            if (state != null) {
                onConversationRemoved?.invoke(id)
            }
        }
        
        // 2. Synchronously wipe disk storage
        storageDir?.listFiles()?.forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete conversation file: ${file.name}", e)
            }
        }
    }

    fun shutdown() {
        cleanupJob?.cancel()
        // In-memory shutdown, persistence remains
        conversations.clear()
    }

    companion object {
        private const val TAG = "ConversationManager"
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L // 30 mins
        const val CLEANUP_INTERVAL_MS = 60 * 1000L // 1 min
    }
}
