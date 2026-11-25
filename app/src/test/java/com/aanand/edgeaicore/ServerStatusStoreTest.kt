package com.aanand.edgeaicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerStatusStoreTest {
    private val first = Any()
    private val second = Any()

    @Before
    fun reset() {
        ServerStatusStore.stop()
    }

    @Test
    fun aPublishFromAReporterThatIsNotCurrentIsDropped() {
        ServerStatusStore.begin(first)
        assertFalse(ServerStatusStore.publish(second, ServerStatus.READY, "ready"))
        assertEquals(ServerStatusSnapshot.OFF, ServerStatusStore.current)
    }

    @Test
    fun aPublishAfterBeginIsStored() {
        ServerStatusStore.begin(first)
        assertTrue(ServerStatusStore.publish(first, ServerStatus.STARTING, "loading"))
        assertEquals(ServerStatusSnapshot(ServerStatus.STARTING, "loading"), ServerStatusStore.current)
    }

    @Test
    fun aLatePublishAfterStopLeavesOff() {
        ServerStatusStore.begin(first)
        ServerStatusStore.publish(first, ServerStatus.STARTING, "loading")
        ServerStatusStore.stop()
        assertFalse(ServerStatusStore.publish(first, ServerStatus.READY, "loaded"))
        assertEquals(ServerStatusSnapshot.OFF, ServerStatusStore.current)
    }

    @Test
    fun beginAgainBySameReporterKeepsItsLaterPublishes() {
        ServerStatusStore.begin(first)
        ServerStatusStore.publish(first, ServerStatus.STARTING, "loading")
        ServerStatusStore.begin(first)
        assertTrue(ServerStatusStore.publish(first, ServerStatus.READY, "loaded"))
        assertEquals(ServerStatus.READY, ServerStatusStore.current.kind)
    }

    @Test
    fun aNewReporterTakesOverAndTheOldOneIsIgnored() {
        ServerStatusStore.begin(first)
        ServerStatusStore.stop()
        ServerStatusStore.begin(second)
        assertTrue(ServerStatusStore.publish(second, ServerStatus.STARTING, "loading"))
        assertFalse(ServerStatusStore.publish(first, ServerStatus.ERROR, "old failure"))
        assertFalse(ServerStatusStore.end(first))
        assertEquals(ServerStatusSnapshot(ServerStatus.STARTING, "loading"), ServerStatusStore.current)
    }

    @Test
    fun endTurnsStartingOrReadyIntoOffAndClearsTheReporter() {
        for (kind in listOf(ServerStatus.STARTING, ServerStatus.READY)) {
            ServerStatusStore.begin(first)
            ServerStatusStore.publish(first, kind, "x")
            assertTrue(ServerStatusStore.end(first))
            assertEquals(ServerStatusSnapshot.OFF, ServerStatusStore.current)
            assertFalse("reporter cleared", ServerStatusStore.publish(first, ServerStatus.READY, "late"))
        }
    }

    @Test
    fun endLeavesAnError() {
        ServerStatusStore.begin(first)
        ServerStatusStore.publish(first, ServerStatus.ERROR, "failed")
        assertFalse(ServerStatusStore.end(first))
        assertEquals(ServerStatusSnapshot(ServerStatus.ERROR, "failed"), ServerStatusStore.current)
        assertFalse(ServerStatusStore.publish(first, ServerStatus.READY, "late"))
    }

    @Test
    fun endIgnoresAReporterThatIsNotCurrent() {
        ServerStatusStore.begin(first)
        ServerStatusStore.publish(first, ServerStatus.READY, "loaded")
        assertFalse(ServerStatusStore.end(second))
        assertEquals(ServerStatus.READY, ServerStatusStore.current.kind)
    }
}
