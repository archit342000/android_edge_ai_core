package com.aanand.edgeaicore

/** The conversation settings a cached engine conversation must match to be reused. */
data class CacheSettings(
    val temperature: Double,
    val topP: Double,
    val topK: Int,
    val systemInstruction: String?,
    val seed: Int = 0
)

/** A cached conversation is identified by its settings and the exact messages it already holds. */
data class CacheKey(val settings: CacheSettings, val messages: List<ChatMessage>)

/**
 * A small LRU cache of engine conversations (and with them their KV caches), so a stateless chat request that only
 * extends the previous one prefills just its new message.
 *
 * [take] hands a conversation out (it is no longer cached while it generates) and [put] returns it with the history it
 * now holds. Entries beyond [maxEntries] (least recently used first) and entries idle for more than [idleTtlMs]
 * (checked lazily on every call) are passed to [close], as are all entries on [clear].
 *
 * Plain Kotlin and thread safe. Closing a LiteRT-LM conversation must happen inside `AiEngineManager.inferenceMutex`,
 * so the manager calls everything here under that lock (the one exception is `AiEngineManager.close()` from the service's `onDestroy`).
 */
class ConversationCache<C>(
    private val maxEntries: Int,
    private val idleTtlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val close: (C) -> Unit
) {
    private class Entry<C>(val key: CacheKey, val conversation: C, val lastUsed: Long)

    private val entries = mutableListOf<Entry<C>>() // oldest first

    /** The number of cached conversations. */
    val size: Int get() = synchronized(entries) { entries.size }

    /** Removes and returns the conversation that holds exactly [context] under [settings], or null on a miss. */
    fun take(settings: CacheSettings, context: List<ChatMessage>): C? = synchronized(entries) {
        evictExpired()
        val key = CacheKey(settings, context)
        val index = entries.indexOfFirst { it.key == key }
        if (index < 0) null else entries.removeAt(index).conversation
    }

    /** Caches [conversation], which now holds [history] under [settings]; evicts the least recently used beyond the cap. */
    fun put(settings: CacheSettings, history: List<ChatMessage>, conversation: C) = synchronized(entries) {
        evictExpired()
        entries.add(Entry(CacheKey(settings, history.toList()), conversation, clock()))
        while (entries.size > maxEntries) close(entries.removeAt(0).conversation)
    }

    /** Closes and forgets every cached conversation. */
    fun clear() = synchronized(entries) {
        entries.forEach { close(it.conversation) }
        entries.clear()
    }

    private fun evictExpired() {
        val now = clock()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.lastUsed > idleTtlMs) {
                iterator.remove()
                close(entry.conversation)
            }
        }
    }
}
