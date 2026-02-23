package com.aanand.edgeaicore

/**
 * Interface to receive streaming updates from inference generation.
 */
interface InferenceCallback {
    /**
     * Called when a new token chunk is generated.
     */
    fun onToken(token: String)

    /**
     * Called when generation is complete.
     */
    fun onComplete(fullResponse: String)

    /**
     * Called when an error occurs.
     */
    fun onError(error: String)
}
