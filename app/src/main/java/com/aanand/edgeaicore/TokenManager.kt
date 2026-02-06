package com.aanand.edgeaicore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages API tokens for client authentication with manual user approval.
 * 
 * Flow:
 * 1. Client app requests token via service.
 * 2. Service captures packageName and adds to [pendingRequests].
 * 3. User manually approves in Edge AI Core UI.
 * 4. Token is generated, added to [tokenMap], and persisted.
 */
class TokenManager(private val context: Context) {
    
    // Mapping of packageName -> token
    private val tokenMap = ConcurrentHashMap<String, String>()
    // Set of packageNames waiting for approval
    private val pendingRequests = ConcurrentHashMap.newKeySet<String>()
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Listener to sync between Service and UI process/activity
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_TOKEN_MAP || key == KEY_PENDING) {
            loadData()
        }
    }
    
    init {
        loadData()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }
    
    private fun loadData() {
        // Load approved tokens
        val json = prefs.getString(KEY_TOKEN_MAP, null)
        val loadedTokenMap = if (json != null) {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type)
        } else {
            emptyMap()
        }
        tokenMap.clear()
        tokenMap.putAll(loadedTokenMap)
        
        // Load pending requests
        val pendingSet = prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet()
        pendingRequests.clear()
        pendingRequests.addAll(pendingSet)
        
        Log.i(TAG, "Sync: Loaded ${tokenMap.size} tokens and ${pendingRequests.size} pending")
    }
    
    private fun persistData() {
        // Unregister listener during write to avoid self-triggering loadData if needed, 
        // but loadData is usually fine with clear/putAll. 
        // Actually onSharedPreferenceChangeListener triggers after the commit/apply.
        prefs.edit()
            .putString(KEY_TOKEN_MAP, gson.toJson(tokenMap))
            .putStringSet(KEY_PENDING, pendingRequests.toSet())
            .apply()
    }

    
    /**
     * Adds a package to the pending requests list if it doesn't already have a token.
     */
    fun requestToken(packageName: String): String {
        val existingToken = tokenMap[packageName]
        if (existingToken != null) {
            return existingToken
        }
        
        if (!pendingRequests.contains(packageName)) {
            pendingRequests.add(packageName)
            persistData()
            Log.i(TAG, "New token request from: $packageName (pending approval)")
        }
        return STATUS_PENDING
    }
    
    /**
     * Force generates a token (used by the main app UI directly).
     */
    fun generateToken(): String {
        val token = UUID.randomUUID().toString()
        val packageName = "manual_${System.currentTimeMillis()}"
        tokenMap[packageName] = token
        persistData()
        Log.i(TAG, "Manually generated token: ${token.take(8)}...")
        return token
    }
    
    /**
     * Approves a pending request from a package.
     */
    fun approveRequest(packageName: String): String? {
        // If it was pending, remove it and generate a token
        if (pendingRequests.contains(packageName)) {
            val token = UUID.randomUUID().toString()
            tokenMap[packageName] = token
            pendingRequests.remove(packageName)
            persistData()
            Log.i(TAG, "Approved token for $packageName: ${token.take(8)}...")
            return token
        }
        return tokenMap[packageName]
    }
    
    /**
     * Logic to deny/remove a pending request.
     */
    fun denyRequest(packageName: String) {
        if (pendingRequests.remove(packageName)) {
            persistData()
            Log.i(TAG, "Denied request from $packageName")
        }
    }
    
    fun isValidToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return tokenMap.values.contains(token)
    }
    
    fun revokeToken(token: String): Boolean {
        var removedKey: String? = null
        for ((pkg, t) in tokenMap) {
            if (t == token) {
                removedKey = pkg
                break
            }
        }
        
        return if (removedKey != null) {
            tokenMap.remove(removedKey)
            persistData()
            Log.i(TAG, "Revoked token for $removedKey")
            true
        } else {
            false
        }
    }
    
    fun getAllTokens(): Set<String> = tokenMap.values.toSet()
    
    fun getTokenMappings(): Map<String, String> = tokenMap.toMap()
    
    fun getPendingRequests(): Set<String> = pendingRequests.toSet()
    
    fun addTokens(tokens: Set<String>) {
        tokens.forEach { token ->
            val pkg = "imported_${UUID.randomUUID().toString().take(4)}"
            tokenMap[pkg] = token
        }
        persistData()
    }

    fun clearAllData() {
        tokenMap.clear()
        pendingRequests.clear()
        persistData()
    }
    
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "edge_ai_core_tokens_v2"
        private const val KEY_TOKEN_MAP = "approved_tokens"
        private const val KEY_PENDING = "pending_requests"
        
        const val STATUS_PENDING = "PENDING_USER_APPROVAL"
    }
}
