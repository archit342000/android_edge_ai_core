package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendFallbackPolicyTest {
    @Test
    fun gpuFallsBackToCpuInAnyCase() {
        assertEquals("CPU", BackendFallbackPolicy.next("GPU"))
        assertEquals("CPU", BackendFallbackPolicy.next("gpu"))
    }

    @Test
    fun cpuAndNpuFailuresAreReportedNotRetried() {
        assertNull(BackendFallbackPolicy.next("CPU"))
        assertNull(BackendFallbackPolicy.next("NPU"))
    }
}
