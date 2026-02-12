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
class TokenManager private constructor(private val context: Context) {
    
    // Mapping of packageName -> token
    private val tokenMap = ConcurrentHashMap<String, String>()
    // Optimization: Fast O(1) lookup set for valid tokens
    private val validTokens = ConcurrentHashMap.newKeySet<String>()
    
    // Set of packageNames waiting for approval
    private val pendingRequests = ConcurrentHashMap.newKeySet<String>()
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dataLock = Any()
    
    init {
        loadData()
    }
    
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "edge_ai_core_tokens_v2"
        private const val KEY_TOKEN_MAP = "approved_tokens"
        private const val KEY_PENDING = "pending_requests"
        private const val BACKUP_FILE_NAME = "auth_tokens_backup.json"
        
        const val STATUS_PENDING = "PENDING_USER_APPROVAL"

        @Volatile
        private var instance: TokenManager? = null

        fun getInstance(context: Context): TokenManager {
            return instance ?: synchronized(this) {
                instance ?: TokenManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun loadData() = synchronized(dataLock) {
        var loadedTokens: Map<String, String>? = null
        
        // 1. Try SharedPreferences
        val json = prefs.getString(KEY_TOKEN_MAP, null)
        if (json != null) {
            val type = object : TypeToken<Map<String, String>>() {}.type
            try {
                loadedTokens = gson.fromJson<Map<String, String>>(json, type)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing tokens from Prefs", e)
            }
        }
        
        // 2. If Prefs are empty/failed, try Secondary Backup File
        if (loadedTokens.isNullOrEmpty()) {
            loadedTokens = loadFromBackupFile()
            if (!loadedTokens.isNullOrEmpty()) {
                Log.i(TAG, "Restored tokens from Backup File (Prefs were empty)")
            }
        }
        
        // 3. Update Memory
        if (!loadedTokens.isNullOrEmpty()) {
            tokenMap.clear()
            validTokens.clear()
            tokenMap.putAll(loadedTokens)
            validTokens.addAll(loadedTokens.values)
        }
        
        // Load pending requests (less critical, stick to Prefs)
        val pendingSet = prefs.getStringSet(KEY_PENDING, emptySet()) ?: emptySet()
        pendingRequests.clear()
        pendingRequests.addAll(pendingSet)
        
        Log.i(TAG, "Sync: Loaded ${tokenMap.size} tokens and ${pendingRequests.size} pending")
    }

    private fun loadFromBackupFile(): Map<String, String>? {
        val file = java.io.File(context.filesDir, BACKUP_FILE_NAME)
        if (!file.exists()) return null
        
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(file.readText(), type)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading backup file", e)
            null
        }
    }
    
    private fun persistTokens(): Boolean = synchronized(dataLock) {
        val json = gson.toJson(tokenMap)
        
        // 1. Save to Prefs
        prefs.edit()
            .putString(KEY_TOKEN_MAP, json)
            .commit()
            
        // 2. Save to Backup File
        saveToBackupFile(json)
        true
    }
    
    private fun saveToBackupFile(json: String) {
        try {
            val file = java.io.File(context.filesDir, BACKUP_FILE_NAME)
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save backup file", e)
        }
    }

    private fun persistPending(): Boolean = synchronized(dataLock) {
        prefs.edit()
            .putStringSet(KEY_PENDING, pendingRequests.toSet())
            .commit() 
    }

    @Suppress("DEPRECATION")
    private fun persistData(): Boolean = synchronized(dataLock) {
        val json = gson.toJson(tokenMap)
        prefs.edit()
            .putString(KEY_TOKEN_MAP, json)
            .putStringSet(KEY_PENDING, pendingRequests.toSet())
            .commit()
            
        // Critical: Also update backup file during approval/full persist
        saveToBackupFile(json)
        true
    }
    
    /**
     * Adds a package to the pending requests list if it doesn't already have a token.
     */
    fun requestToken(packageName: String): String = synchronized(dataLock) {
        val existingToken = tokenMap[packageName]
        if (existingToken != null) {
            return existingToken
        }
        
        if (!pendingRequests.contains(packageName)) {
            pendingRequests.add(packageName)
            persistPending() // Only update pending requests
            Log.i(TAG, "New token request from: $packageName (pending approval)")
        }
        return STATUS_PENDING
    }
    
    /**
     * Force generates a token (used by the main app UI directly).
     */
    fun generateToken(): String = synchronized(dataLock) {
        val token = UUID.randomUUID().toString()
        val packageName = "manual_${System.currentTimeMillis()}"
        tokenMap[packageName] = token
        validTokens.add(token)
        persistTokens()
        Log.i(TAG, "Manually generated token: ${token.take(8)}...")
        return token
    }
    
    /**
     * Approves a pending request from a package.
     */
    fun approveRequest(packageName: String): String? = synchronized(dataLock) {
        // If it was pending, remove it and generate a token
        if (pendingRequests.contains(packageName)) {
            val token = UUID.randomUUID().toString()
            tokenMap[packageName] = token
            validTokens.add(token)
            pendingRequests.remove(packageName)
            @Suppress("DEPRECATION")
            persistData() // Update both as we modified both
            Log.i(TAG, "Approved token for $packageName: ${token.take(8)}...")
            return token
        }
        return tokenMap[packageName]
    }
    
    /**
     * Logic to deny/remove a pending request.
     */
    fun denyRequest(packageName: String) = synchronized(dataLock) {
        if (pendingRequests.remove(packageName)) {
            persistPending()
            Log.i(TAG, "Denied request from $packageName")
        }
    }
    
    fun isValidToken(token: String?): Boolean = synchronized(dataLock) {
        if (token.isNullOrBlank()) return false
        val sanitized = token.trim()
        val valid = validTokens.contains(sanitized)
        if (!valid) {
            Log.w(TAG, "Token validation failed for: ${sanitized.take(8)}...")
        }
        return valid
    }
    
    fun revokeToken(token: String): Boolean = synchronized(dataLock) {
        var removedKey: String? = null
        for ((pkg, t) in tokenMap) {
            if (t == token) {
                removedKey = pkg
                break
            }
        }
        
        if (removedKey != null) {
            tokenMap.remove(removedKey)
            validTokens.remove(token)
            persistTokens()
            Log.i(TAG, "Revoked token for $removedKey")
            true
        } else {
            false
        }
    }

    fun revokeTokenByPackage(packageName: String): Boolean = synchronized(dataLock) {
        val token = tokenMap.remove(packageName)
        if (token != null) {
            validTokens.remove(token)
            persistTokens()
            Log.i(TAG, "Revoked token for $packageName")
            true
        } else {
            false
        }
    }
    
    fun getAllTokens(): Set<String> = synchronized(dataLock) {
        tokenMap.values.toSet()
    }
    
    fun getTokenMappings(): Map<String, String> = synchronized(dataLock) {
        tokenMap.toMap()
    }
    
    fun getPendingRequests(): Set<String> = synchronized(dataLock) {
        pendingRequests.toSet()
    }
    
    fun addTokens(tokens: Set<String>) = synchronized(dataLock) {
        tokens.forEach { token ->
            val pkg = "imported_${UUID.randomUUID().toString().take(4)}"
            tokenMap[pkg] = token
            validTokens.add(token)
        }
        persistTokens()
    }

    fun clearAllData() {
        synchronized(dataLock) {
            tokenMap.clear()
            validTokens.clear()
            pendingRequests.clear()
            @Suppress("DEPRECATION")
            persistData()
        }
    }
    
    // Re-add loadData and addTokens with proper locking
    fun forceReload() = loadData()
    
}
