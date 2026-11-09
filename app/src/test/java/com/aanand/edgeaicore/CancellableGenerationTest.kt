package com.aanand.edgeaicore

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableGenerationTest {

    @Test
    fun cancelRunsTheActionOnlyOnce() {
        var calls = 0
        val generation = CancellableGeneration { calls++ }
        assertFalse(generation.wasCancelled)
        generation.cancel()
        generation.cancel()
        assertEquals(1, calls)
        assertTrue(generation.wasCancelled)
    }

    @Test
    fun awaitStoppedReturnsAtOnceWhenNeverCancelled() = runTest {
        val generation = CancellableGeneration { }
        assertTrue(generation.awaitStopped(1_000))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun awaitStoppedWaitsForTheTerminalCallback() = runTest {
        val generation = CancellableGeneration { }
        generation.cancel()
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { generation.awaitStopped(1_000) }
        advanceTimeBy(500)
        assertFalse(stopped.isCompleted)
        generation.finish()
        runCurrent()
        assertTrue(stopped.await())
    }

    @Test
    fun awaitStoppedGivesUpAfterTheBound() = runTest {
        val generation = CancellableGeneration { }
        generation.cancel()
        assertFalse(generation.awaitStopped(1_000))
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun awaitStoppedStillWaitsInsideACancelledCoroutine() = runTest {
        val generation = CancellableGeneration { }
        generation.cancel()
        var result: Boolean? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                result = generation.awaitStopped(1_000)
            }
        }
        job.cancel()
        runCurrent()
        assertEquals(null, result) // still waiting for the native callback
        generation.finish()
        job.cancelAndJoin()
        assertEquals(true, result)
    }

    @Test
    fun msSinceCancelMeasuresFromTheFirstCancel() {
        var now = 1_000L
        val generation = CancellableGeneration(clockMs = { now }) { }
        assertEquals(0L, generation.msSinceCancel())
        generation.cancel()
        now = 1_250L
        generation.cancel() // a second cancel does not restart the clock
        assertEquals(250L, generation.msSinceCancel())
    }
}
