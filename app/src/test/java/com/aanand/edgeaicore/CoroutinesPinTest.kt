package com.aanand.edgeaicore

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards the kotlinx-coroutines pin in `app/build.gradle.kts` (`docs/pitfalls.md` 26).
 * Ktor 2.3.12 reads large request bodies through this method; coroutines 1.11.0 removed it.
 */
class CoroutinesPinTest {
    @Test
    fun ktorEventLoopMethodIsOnTheClasspath() {
        val method = Class.forName("kotlinx.coroutines.EventLoopKt")
            .getMethod("processNextEventInCurrentThread")
        assertNotNull(method)
    }
}
