package com.aanand.edgeaicore

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the engine is generating a reply right now, for the UI to step aside: the dot sphere draws at the display rate,
 * except while a reply is being generated, when it shares the CPU with the model and draws slowly. `AiEngineManager` calls
 * [begin] once it holds the inference lock and [end] before releasing it. Thread-safe.
 */
object GenerationTracker {
    private val running = AtomicInteger(0)

    /** True while at least one reply is being generated. */
    val active: Boolean get() = running.get() > 0

    fun begin() {
        running.incrementAndGet()
    }

    fun end() {
        running.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /**
     * How long the sphere waits between frames, in ms: every frame (0) normally; [generatingMs] while a reply is generated,
     * shortened to [touchedMs] for a moment after a touch or while a ripple runs.
     */
    fun sphereFrameIntervalMs(generating: Boolean, touched: Boolean, generatingMs: Int, touchedMs: Int): Int = when {
        !generating -> 0
        touched -> touchedMs
        else -> generatingMs
    }
}
