package com.aanand.edgeaicore

/**
 * The one place the server's status is kept. `InferenceService` reports into it and `MainActivity` reads it on every status
 * broadcast and in `onResume`, so a status reached while the activity was in the background is not lost.
 *
 * Only the current reporter (the service instance that handled the latest start) may change the status, which keeps a model
 * load that finishes after a stop, or a late report from an old instance, from overwriting a newer state. All calls are
 * thread-safe; the service reports from its IO coroutines.
 */
object ServerStatusStore {
    private val lock = Any()
    private var reporter: Any? = null
    private var snapshot = ServerStatusSnapshot.OFF

    /** The latest stored status. */
    val current: ServerStatusSnapshot
        get() = synchronized(lock) { snapshot }

    /** Makes [reporter] the instance allowed to report. Called for every start that is not a stop. */
    fun begin(reporter: Any) {
        synchronized(lock) { this.reporter = reporter }
    }

    /** Stores the status if [reporter] is the current one; returns whether it was stored. */
    fun publish(reporter: Any, kind: ServerStatus, detail: String): Boolean = synchronized(lock) {
        if (this.reporter !== reporter) return false
        snapshot = ServerStatusSnapshot(kind, detail)
        true
    }

    /** A stop was asked for: nobody may report until the next start, and the server is off. */
    fun stop() {
        synchronized(lock) {
            reporter = null
            snapshot = ServerStatusSnapshot.OFF
        }
    }

    /**
     * [reporter] is being destroyed. For the current reporter, a starting or ready status becomes off and an error stays;
     * any other reporter is ignored. Returns whether off was stored.
     */
    fun end(reporter: Any): Boolean = synchronized(lock) {
        if (this.reporter !== reporter) return false
        this.reporter = null
        if (snapshot.kind == ServerStatus.STARTING || snapshot.kind == ServerStatus.READY) {
            snapshot = ServerStatusSnapshot.OFF
            true
        } else {
            false
        }
    }
}
