package com.aanand.edgeaicore;

import com.aanand.edgeaicore.IInferenceCallback;

interface IInferenceService {
    // =====================
    // Token Management
    // =====================

    /**
     * Generates a new API token for authentication.
     * This token must be included in all subsequent requests.
     * @return A unique UUID token string
     */
    String generateApiToken();

    /**
     * Revokes an API token, invalidating it and closing all associated sessions.
     * @param apiToken The token to revoke
     * @return true if the token was revoked, false if not found
     */
    boolean revokeApiToken(String apiToken);

    // =====================
    // Session Management
    // =====================

    /**
     * Starts a new session for the client.
     * Sessions maintain conversation state and enable KV cache reuse.
     * 
     * @param apiToken The API token for authentication
     * @param ttlMs Optional TTL in milliseconds (0 for default: 30 minutes)
     * @return JSON string containing session info: {"session_id": "...", "ttl_ms": N, "created_at": N}
     *         or error: {"error": "..."}
     */
    String startSession(String apiToken, long ttlMs);

    /**
     * Closes a session, releasing its resources and invalidating the session ID.
     * Always call this when done with a session to free up memory.
     * 
     * @param apiToken The API token for authentication
     * @param sessionId The session to close
     * @return JSON string: {"success": true} or {"error": "..."}
     */
    String closeSession(String apiToken, String sessionId);

    /**
     * Gets information about an active session.
     * 
     * @param apiToken The API token for authentication
     * @param sessionId The session to query
     * @return JSON string with session info or error
     */
    String getSessionInfo(String apiToken, String sessionId);

    // =====================
    // Inference (Session-Based)
    // =====================

    /**
     * Generates a response within an existing session (synchronous).
     * The session's conversation state is preserved, enabling KV cache reuse.
     * Each call resets the session's TTL.
     * 
     * @param apiToken The API token for authentication
     * @param sessionId The session ID from startSession
     * @param jsonRequest The chat completion request JSON
     * @return The chat completion response JSON or error JSON
     */
    String generateResponseWithSession(String apiToken, String sessionId, String jsonRequest);

    /**
     * Generates a streaming response within an existing session (asynchronous).
     * The session's conversation state is preserved, enabling KV cache reuse.
     * Each call resets the session's TTL.
     * 
     * @param apiToken The API token for authentication
     * @param sessionId The session ID from startSession
     * @param jsonRequest The chat completion request JSON
     * @param callback The callback for receiving tokens, completion, or errors
     */
    void generateResponseAsyncWithSession(String apiToken, String sessionId, String jsonRequest, IInferenceCallback callback);
    /**
     * Diagnostic method to check if the service is alive and responsive.
     * @return The string "pong"
     */
    String ping();
}
