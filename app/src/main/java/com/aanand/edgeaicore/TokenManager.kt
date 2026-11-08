package com.aanand.edgeaicore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages API tokens for client authentication.
 * 
 * Flow:
 * 1. Tokens are generated manually or via backup restore.
 * 2. Only ONE token is active at a time. Generating a new one clears the old.
 * 3. Tokens are persisted and used for API validation.
 */
class TokenManager private constructor(private val context: Context) {
    
    // Mapping of packageName -> token
    private val tokenMap = ConcurrentHashMap<String, String>()
    // Optimization: Fast O(1) lookup set for valid tokens
    private val validTokens = ConcurrentHashMap.newKeySet<String>()
    
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
        private const val BACKUP_FILE_NAME = "auth_tokens_backup.json"
        
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
        tokenMap.clear()
        validTokens.clear()
        if (!loadedTokens.isNullOrEmpty()) {
            // Enforce single token rule on load if somehow multiple exist
            // (Takes the last one to be consistent)
            val entry = loadedTokens.entries.last()
            tokenMap[entry.key] = entry.value
            validTokens.add(entry.value)
        }
        
        Log.i(TAG, "Sync: Loaded ${tokenMap.size} tokens")
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
    
    /**
     * Force generates a token (used by the main app UI directly).
     * Clears all previous tokens to enforce "One active token" rule.
     */
    fun generateToken(): String = synchronized(dataLock) {
        clearAllData() // Enforce single token

        val token = UUID.randomUUID().toString()
        val packageName = "manual_${System.currentTimeMillis()}"
        tokenMap[packageName] = token
        validTokens.add(token)
        persistTokens()
        Log.i(TAG, "Manually generated token: ${token.take(8)}...")
        return token
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
    
    fun addTokens(tokens: Set<String>) = synchronized(dataLock) {
        clearAllData() // Enforce single token

        // Only take the first token from the set if multiple exist
        val token = tokens.firstOrNull()
        if (token != null) {
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
            persistTokens()
        }
    }
    
    // Re-add loadData and addTokens with proper locking
    fun forceReload() = loadData()
    
}
