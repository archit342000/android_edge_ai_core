package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativeDecodingOptionTest {

    @Test
    fun anUnsupportedModelGetsNoModeForAnyOption() {
        for (option in SpeculativeDecodingOption.entries) {
            assertNull(option.name, SpeculativeDecodingOption.toConfigMode(option, supported = false))
        }
    }

    @Test
    fun offGivesNoModeEvenForASupportedModel() {
        assertNull(SpeculativeDecodingOption.toConfigMode(SpeculativeDecodingOption.OFF, supported = true))
    }

    @Test
    fun onAndAdaptiveMapToTheirModesForASupportedModel() {
        assertEquals(SpeculativeDecodingMode.ON, SpeculativeDecodingOption.toConfigMode(SpeculativeDecodingOption.ON, true))
        assertEquals(SpeculativeDecodingMode.ADAPTIVE, SpeculativeDecodingOption.toConfigMode(SpeculativeDecodingOption.ADAPTIVE, true))
    }

    @Test
    fun storedValuesParseCaseInsensitivelyAndDefaultToAdaptive() {
        assertEquals(SpeculativeDecodingOption.OFF, SpeculativeDecodingOption.fromStored("OFF"))
        assertEquals(SpeculativeDecodingOption.ON, SpeculativeDecodingOption.fromStored("on"))
        assertEquals(SpeculativeDecodingOption.ADAPTIVE, SpeculativeDecodingOption.fromStored("Adaptive"))
        assertEquals(SpeculativeDecodingOption.ADAPTIVE, SpeculativeDecodingOption.fromStored(null))
        assertEquals(SpeculativeDecodingOption.ADAPTIVE, SpeculativeDecodingOption.fromStored("bogus"))
    }

    @Test
    fun onlyANonNullUnrecognisedValueIsFlaggedUnknown() {
        assertFalse(SpeculativeDecodingOption.isUnknown(null))
        assertFalse(SpeculativeDecodingOption.isUnknown("adaptive"))
        assertTrue(SpeculativeDecodingOption.isUnknown("bogus"))
    }
}
