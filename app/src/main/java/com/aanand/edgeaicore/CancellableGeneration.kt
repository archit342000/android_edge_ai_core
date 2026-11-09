package com.aanand.edgeaicore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks one native generation so it can be cancelled and then awaited.
 *
 * The native engine reports the end of a generation through a callback (`onDone` or `onError`). After a cancel that
 * callback still arrives, and the next request may only start once it has, so the caller [cancel]s, then
 * [awaitStopped] before releasing the inference lock. The native callback calls [finish].
 *
 * @param cancelAction asks the engine to stop (for example `cancelProcess()`); runs at most once.
 */
internal class CancellableGeneration(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val cancelAction: () -> Unit
) {
    @Volatile private var cancelledAtMs = 0L

    private val terminal = CompletableDeferred<Unit>()
    private val cancelRequested = AtomicBoolean(false)

    /** True once [cancel] has been called. */
    val wasCancelled: Boolean get() = cancelRequested.get()

    /** Asks the engine to stop. Safe to call from a cancellation handler; only the first call has an effect. */
    fun cancel() {
        if (cancelRequested.compareAndSet(false, true)) {
            cancelledAtMs = clockMs()
            cancelAction()
        }
    }

    /** Milliseconds since [cancel] was first called, or 0 if it never was. */
    fun msSinceCancel(): Long {
        val at = cancelledAtMs // still 0 for an instant after wasCancelled flips
        return if (at == 0L) 0L else clockMs() - at
    }

    /** Records that the native generation has ended (called from `onDone` and `onError`). */
    fun finish() {
        terminal.complete(Unit)
    }

    /**
     * Waits, even inside a cancelled coroutine, until the native generation has ended or [waitMs] has passed.
     * Returns true if it ended, false on timeout. Returns true at once when [cancel] was never called.
     */
    suspend fun awaitStopped(waitMs: Long): Boolean {
        if (!wasCancelled) return true
        return withContext(NonCancellable) { withTimeoutOrNull(waitMs) { terminal.await() } != null }
    }
}
