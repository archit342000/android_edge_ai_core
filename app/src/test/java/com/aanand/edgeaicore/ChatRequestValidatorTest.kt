package com.aanand.edgeaicore

import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRequestValidatorTest {

    private val message = ChatMessage("user", JsonPrimitive("hi"))

    private fun request(
        messages: List<ChatMessage> = listOf(message),
        temperature: Double? = null,
        topP: Double? = null,
        topK: Int? = null,
        presence: Double? = null,
        frequency: Double? = null,
        maxTokens: Int? = null,
        maxCompletionTokens: Int? = null
    ) = ChatCompletionRequest(
        messages = messages, temperature = temperature, top_p = topP, top_k = topK,
        presence_penalty = presence, frequency_penalty = frequency,
        max_tokens = maxTokens, max_completion_tokens = maxCompletionTokens
    )

    private fun accepted(r: ChatCompletionRequest) = assertNull(ChatRequestValidator.validate(r))
    private fun rejected(r: ChatCompletionRequest) = assertNotNull(ChatRequestValidator.validate(r))

    @Test
    fun aPlainRequestIsAccepted() = accepted(request())

    @Test
    fun messagesMustBeBetweenOneAndTheLimit() {
        rejected(request(messages = emptyList()))
        accepted(request(messages = List(ChatRequestValidator.MAX_MESSAGES) { message }))
        rejected(request(messages = List(ChatRequestValidator.MAX_MESSAGES + 1) { message }))
    }

    @Test
    fun temperatureBoundsAreInclusive() {
        accepted(request(temperature = 0.0))
        accepted(request(temperature = 2.0))
        rejected(request(temperature = -0.1))
        rejected(request(temperature = 2.1))
        rejected(request(temperature = Double.NaN))
    }

    @Test
    fun topPMustBeGreaterThanZeroAndAtMostOne() {
        accepted(request(topP = 0.01))
        accepted(request(topP = 1.0))
        rejected(request(topP = 0.0))
        rejected(request(topP = 1.01))
    }

    @Test
    fun topKMustBeAtLeastOne() {
        accepted(request(topK = 1))
        rejected(request(topK = 0))
    }

    @Test
    fun penaltiesAreBetweenMinusTwoAndTwo() {
        accepted(request(presence = -2.0, frequency = 2.0))
        rejected(request(presence = 2.01))
        rejected(request(frequency = -2.01))
        rejected(request(presence = Double.NaN))
    }

    @Test
    fun maxTokensMustBePositive() {
        accepted(request(maxTokens = 1))
        rejected(request(maxTokens = 0))
        rejected(request(maxCompletionTokens = 0))
        rejected(request(maxTokens = 5, maxCompletionTokens = -1))
    }

    @Test
    fun maxCompletionTokensWinsOverMaxTokens() {
        assertEquals(7, request(maxTokens = 3, maxCompletionTokens = 7).maxOutputTokens)
        assertEquals(3, request(maxTokens = 3).maxOutputTokens)
        assertNull(request().maxOutputTokens)
    }

    @Test
    fun onlyOneChoiceIsSupported() {
        for (n in listOf(null, 1)) assertNull(ChatRequestValidator.validate(request().copy(n = n)))
        for (n in listOf(0, 2, -1)) assertEquals("n must be 1", ChatRequestValidator.validate(request().copy(n = n)))
    }
}
