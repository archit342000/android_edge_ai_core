package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextSizeInputTest {
    @Test
    fun emptyIsTheModelDefault() {
        assertEquals(ContextSizeInput.ModelDefault, ContextSizeInput.parse(""))
        assertEquals(ContextSizeInput.ModelDefault, ContextSizeInput.parse("   "))
        assertEquals(0, ContextSizeInput.toStored(ContextSizeInput.ModelDefault))
    }

    @Test
    fun aWholeNumberAboveZeroIsTokens() {
        assertEquals(ContextSizeInput.Tokens(4096), ContextSizeInput.parse(" 4096 "))
        assertEquals(4096, ContextSizeInput.toStored(ContextSizeInput.Tokens(4096)))
    }

    @Test
    fun zeroNegativeAndTextAreInvalid() {
        for (text in listOf("0", "-5", "abc", "12a", "1.5")) assertEquals(text, ContextSizeInput.Invalid, ContextSizeInput.parse(text))
        assertNull(ContextSizeInput.toStored(ContextSizeInput.Invalid))
    }
}
