package com.aanand.edgeaicore

import com.google.ai.edge.litertlm.BackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderBackendSelectorTest {
    private val both = listOf(BackendType.CPU, BackendType.GPU)

    @Test
    fun noEncoderGivesNoBackendForAnyOption() {
        for (option in EncoderBackendOption.entries) {
            assertNull(EncoderBackendSelector.select(null, "CPU", option).backend)
        }
    }

    @Test
    fun autoFollowsTheMainBackendWhenSupported() {
        assertEquals(BackendType.GPU, EncoderBackendSelector.select(both, "GPU", EncoderBackendOption.AUTO).backend)
        assertEquals(BackendType.CPU, EncoderBackendSelector.select(both, "CPU", EncoderBackendOption.AUTO).backend)
    }

    @Test
    fun autoFallsBackToCpuThenGpuWhenTheMainBackendIsNotSupported() {
        assertEquals(BackendType.CPU, EncoderBackendSelector.select(listOf(BackendType.CPU), "GPU", EncoderBackendOption.AUTO).backend)
        assertEquals(BackendType.GPU, EncoderBackendSelector.select(listOf(BackendType.GPU), "CPU", EncoderBackendOption.AUTO).backend)
    }

    @Test
    fun anNpuMainBackendCountsAsCpu() {
        assertEquals(BackendType.CPU, EncoderBackendSelector.select(both, "NPU", EncoderBackendOption.AUTO).backend)
        assertEquals(BackendType.GPU, EncoderBackendSelector.select(listOf(BackendType.GPU), "NPU", EncoderBackendOption.AUTO).backend)
    }

    @Test
    fun aSupportedOverrideWins() {
        val selection = EncoderBackendSelector.select(both, "GPU", EncoderBackendOption.CPU)
        assertEquals(BackendType.CPU, selection.backend)
        assertFalse(selection.overrideIgnored)
    }

    @Test
    fun anUnsupportedOverrideFallsBackAndIsFlagged() {
        val selection = EncoderBackendSelector.select(listOf(BackendType.GPU), "CPU", EncoderBackendOption.CPU)
        assertEquals(BackendType.GPU, selection.backend)
        assertTrue(selection.overrideIgnored)
    }

    @Test
    fun usableBackendsNormaliseEmptyDropNpuAndTurnNpuOnlyIntoOff() {
        assertNull(EncoderBackendSelector.usableBackends(present = false, fromModel = both))
        assertEquals(both, EncoderBackendSelector.usableBackends(present = true, fromModel = emptyList()))
        assertEquals(listOf(BackendType.GPU), EncoderBackendSelector.usableBackends(true, listOf(BackendType.NPU, BackendType.GPU)))
        assertNull(EncoderBackendSelector.usableBackends(true, listOf(BackendType.NPU)))
        assertEquals(listOf(BackendType.CPU), EncoderBackendSelector.usableBackends(true, listOf(BackendType.CPU, BackendType.CPU)))
    }

    @Test
    fun storedOptionsParseAndDefaultToAuto() {
        assertEquals(EncoderBackendOption.GPU, EncoderBackendOption.fromStored("gpu"))
        assertEquals(EncoderBackendOption.CPU, EncoderBackendOption.fromStored("CPU"))
        assertEquals(EncoderBackendOption.AUTO, EncoderBackendOption.fromStored(null))
        assertEquals(EncoderBackendOption.AUTO, EncoderBackendOption.fromStored("bogus"))
        assertTrue(EncoderBackendOption.isUnknown("bogus"))
        assertFalse(EncoderBackendOption.isUnknown(null))
    }

    @Test
    fun theSummaryDescribesBothEncoders() {
        assertEquals("vision CPU, audio off", EncoderSummary(BackendType.CPU, null).describe())
        assertEquals("vision off (override ignored), audio GPU", EncoderSummary(null, BackendType.GPU, visionOverrideIgnored = true).describe())
        assertTrue(EncoderSummary(BackendType.CPU, null).supportsImages)
        assertFalse(EncoderSummary(BackendType.CPU, null).supportsAudio)
    }
}
