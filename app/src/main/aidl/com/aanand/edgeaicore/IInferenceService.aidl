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
     * Revokes an API token, invalidating it and closing all associated conversations.
     * @param apiToken The token to revoke
     * @return true if the token was revoked, false if not found
     */
    boolean revokeApiToken(String apiToken);

    // =====================
    // Conversation Management
    // =====================

    /**
     * Starts a new stateful conversation for the client.
     * 
     * @param apiToken The API token for authentication
     * @return JSON string containing conversation info: {"conversation_id": "...", "ttl_ms": N, "created_at": N}
     *         or error: {"error": "..."}
     */
    String startConversation(String apiToken, String systemInstruction);

    /**
     * Closes a conversation, releasing its resources and invalidating the ID.
     * 
     * @param apiToken The API token for authentication
     * @param conversationId The conversation to close
     * @return JSON string: {"success": true} or {"error": "..."}
     */
    String closeConversation(String apiToken, String conversationId);

    /**
     * Gets information about an active conversation.
     * 
     * @param apiToken The API token for authentication
     * @param conversationId The conversation to query
     * @return JSON string with conversation info or error
     */
    String getConversationInfo(String apiToken, String conversationId);

    // =====================
    // Inference
    // =====================

    /**
     * Generates a streaming response within an existing conversation (asynchronous).
     * Only new messages in the conversation should be sent in jsonRequest.
     * Sending the full conversation history can result in unexpected behavior.
     * 
     * @param apiToken The API token for authentication
     * @param conversationId The ID from startConversation
     * @param jsonRequest The chat completion request JSON (containing only new messages)
     * @param callback The callback for receiving tokens, completion, or errors
     */
    void generateConversationResponseAsync(String apiToken, String conversationId, String jsonRequest, IInferenceCallback callback);

    /**
     * Diagnostic method to check if the service is alive and responsive.
     * @return The string "pong"
     */
    String ping();
}
