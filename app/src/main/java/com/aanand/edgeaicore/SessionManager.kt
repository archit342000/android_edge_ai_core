package com.aanand.edgeaicore

import android.util.Log
import com.google.ai.edge.litertlm.Session as LiteRTSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a client session with its associated LiteRT-LM session.
 * 
 * @property sessionId Unique identifier for this session
 * @property apiToken The API token that owns this session
 * @property engineSession The LiteRT-LM Session object for KV cache reuse and prefill control
 * @property ttlMs Time-to-live in milliseconds
 * @property lastAccessTime Timestamp of the last access (for TTL calculation)
 * @property createdAt Timestamp when the session was created
 */
data class Session(
    val sessionId: String,
    val apiToken: String,
    var engineSession: LiteRTSession?,
    val ttlMs: Long,
    var lastAccessTime: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    var processedMessageCount: Int = 0
) {
    /**
     * Checks if this session has expired.
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastAccessTime > ttlMs
    }
    
    /**
     * Updates the last access time to now, effectively resetting the TTL.
     */
    fun touch() {
        lastAccessTime = System.currentTimeMillis()
    }
}

/**
 * Manages client sessions with TTL (Time-To-Live) support.
 * 
 * Each session maintains a LiteRT-LM Session object to leverage
 * KV caching for efficient multi-turn conversations and prefill control.
 * 
 * Sessions expire after a configurable TTL if not accessed, allowing
 * cleanup of stale sessions when clients forget to close them.
 */
class SessionManager(
    private val defaultTtlMs: Long = DEFAULT_SESSION_TTL_MS,
    private val cleanupIntervalMs: Long = CLEANUP_INTERVAL_MS
) {
    
    private val sessions = ConcurrentHashMap<String, Session>()
    private var cleanupJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    
    init {
        startCleanupTask()
    }
    
    /**
     * Creates a new session for the given API token.
     * 
     * @param apiToken The API token that owns this session
     * @param ttlMs Optional custom TTL in milliseconds (uses default if not specified)
     * @return The newly created Session
     */
    fun createSession(apiToken: String, ttlMs: Long = defaultTtlMs): Session {
        val sessionId = UUID.randomUUID().toString()
        val session = Session(
            sessionId = sessionId,
            apiToken = apiToken,
            engineSession = null, // Will be set when first inference runs
            ttlMs = ttlMs
        )
        sessions[sessionId] = session
        Log.i(TAG, "Created session: ${sessionId.take(8)}... for token: ${apiToken.take(8)}... (TTL: ${ttlMs}ms)")
        return session
    }
    
    /**
     * Gets a session by its ID, returning null if not found or expired.
     * Also validates that the session belongs to the given API token.
     * 
     * @param sessionId The session ID to look up
     * @param apiToken The API token making the request
     * @return The Session if found, valid, and owned by the token; null otherwise
     */
    fun getSession(sessionId: String, apiToken: String): Session? {
        val session = sessions[sessionId]
        
        if (session == null) {
            Log.w(TAG, "Session not found: ${sessionId.take(8)}...")
            return null
        }
        
        if (session.apiToken != apiToken) {
            Log.w(TAG, "Session ${sessionId.take(8)}... does not belong to token ${apiToken.take(8)}...")
            return null
        }
        
        if (session.isExpired()) {
            Log.w(TAG, "Session expired: ${sessionId.take(8)}...")
            closeSession(sessionId, apiToken)
            return null
        }
        
        // Reset TTL on access
        session.touch()
        return session
    }
    
    /**
     * Closes a session, cleaning up its resources.
     * 
     * @param sessionId The session ID to close
     * @param apiToken The API token making the request
     * @return true if the session was closed, false if not found or unauthorized
     */
    fun closeSession(sessionId: String, apiToken: String): Boolean {
        val session = sessions[sessionId]
        
        if (session == null) {
            Log.w(TAG, "Cannot close session - not found: ${sessionId.take(8)}...")
            return false
        }
        
        if (session.apiToken != apiToken) {
            Log.w(TAG, "Cannot close session - unauthorized: ${sessionId.take(8)}...")
            return false
        }
        
        return removeSession(sessionId)
    }
    
    /**
     * Gets all active session IDs for a given API token.
     */
    fun getSessionsForToken(apiToken: String): List<String> {
        return sessions.values
            .filter { it.apiToken == apiToken && !it.isExpired() }
            .map { it.sessionId }
    }
    
    /**
     * Closes all sessions for a given API token.
     * Useful when a token is revoked.
     */
    fun closeAllSessionsForToken(apiToken: String): Int {
        val sessionsToClose = getSessionsForToken(apiToken)
        sessionsToClose.forEach { removeSession(it) }
        Log.i(TAG, "Closed ${sessionsToClose.size} sessions for token: ${apiToken.take(8)}...")
        return sessionsToClose.size
    }
    
    /**
     * Returns the count of currently active sessions.
     */
    fun getActiveSessionCount(): Int = sessions.count { !it.value.isExpired() }
    
    /**
     * Sets the LiteRT session object for a session.
     * Called when a new inference request creates or reuses a session.
     */
    fun setEngineSession(sessionId: String, engineSession: LiteRTSession) {
        sessions[sessionId]?.engineSession = engineSession
    }
    
    /**
     * Internal method to remove a session and clean up its resources.
     */
    private fun removeSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId)
        if (session != null) {
            try {
                session.engineSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing engine session for session ${sessionId.take(8)}...", e)
            }
            Log.i(TAG, "Removed session: ${sessionId.take(8)}...")
            return true
        }
        return false
    }
    
    /**
     * Starts the background cleanup task for expired sessions.
     */
    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                cleanupExpiredSessions()
            }
        }
        Log.i(TAG, "Started session cleanup task (interval: ${cleanupIntervalMs}ms)")
    }
    
    /**
     * Cleans up all expired sessions.
     */
    private fun cleanupExpiredSessions() {
        val expiredSessions = sessions.filter { it.value.isExpired() }
        if (expiredSessions.isNotEmpty()) {
            Log.i(TAG, "Cleaning up ${expiredSessions.size} expired sessions")
            expiredSessions.keys.forEach { removeSession(it) }
        }
    }
    
    /**
     * Shuts down the session manager, closing all sessions and stopping cleanup.
     */
    fun shutdown() {
        cleanupJob?.cancel()
        val count = sessions.size
        sessions.keys.toList().forEach { removeSession(it) }
        Log.i(TAG, "Shutdown complete - closed $count sessions")
    }
    
    companion object {
        private const val TAG = "SessionManager"
        
        // Default TTL: 30 minutes
        const val DEFAULT_SESSION_TTL_MS = 30 * 60 * 1000L
        
        // Cleanup interval: 1 minute
        const val CLEANUP_INTERVAL_MS = 60 * 1000L
    }
}
