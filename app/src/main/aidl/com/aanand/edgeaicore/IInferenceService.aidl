package com.aanand.edgeaicore;

import com.aanand.edgeaicore.IInferenceCallback;

interface IInferenceService {
    /**
     * Generates a response based on the input JSON request.
     * The input should be a JSON string representing a chat completion request.
     * The output will be a JSON string representing the chat completion response.
     */
    String generateResponse(String jsonRequest);

    /**
     * Generates a streaming response for the given request.
     */
    void generateResponseAsync(String jsonRequest, IInferenceCallback callback);
}
