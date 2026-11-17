package org.mozkey.inputmethod.engine

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phrase-level input history, owned by the app layer.
 *
 * Mozc's own user-history predictor works per segment, so it cannot offer a
 * whole sentence the user committed before. This store remembers
 * (reading -> committed text) pairs at the moment a composition is committed
 * and answers prefix queries against the reading: after committing
 * いまからかえるよ -> 今から帰るよ once, typing い already offers 今から帰るよ.
 *
 * Persistence is a small TSV file (reading, value, use count, last-used time,
 * with backslash escaping). Writes are coalesced onto a single background
 * thread; the in-memory map is the source of truth while the process lives.
 * This class has no Android dependencies so it can be unit-tested on the JVM.
 */
class PhraseHistory(private val file: File) {

    data class Entry(val reading: String, val value: String, val count: Int, val lastUsed: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var loaded = false

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mozkey-phrase-history").apply { isDaemon = true }
    }
    private val savePending = AtomicBoolean(false)

    /** Remembers one committed phrase. Unconverted text (reading == value) is not a phrase. */
    fun record(reading: String, value: String, now: Long = System.currentTimeMillis()) {
        if (reading.length < MIN_READING_LENGTH || value.length < MIN_VALUE_LENGTH) return
        if (reading == value) return
        synchronized(lock) {
            ensureLoadedLocked()
            val key = keyOf(reading, value)
            val old = entries[key]
            entries[key] = Entry(reading, value, (old?.count ?: 0) + 1, now)
            trimLocked()
        }
        scheduleSave()
    }

    /**
     * Phrases whose reading starts with [query], most useful first
     * (use count, then recency). Exact-reading matches are included.
     */
    fun suggest(query: String, limit: Int = MAX_SUGGESTIONS): List<Entry> {
        if (query.isEmpty()) return emptyList()
        synchronized(lock) {
            ensureLoadedLocked()
            return entries.values
                .filter { it.reading.startsWith(query) && it.value != query }
                .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
                .distinctBy { it.value }
                .take(limit)
        }
    }

    fun size(): Int = synchronized(lock) {
        ensureLoadedLocked()
        entries.size
    }

    fun clear() {
        synchronized(lock) {
            ensureLoadedLocked()
            entries.clear()
        }
        scheduleSave()
    }

    /** Asks the writer thread to persist the current state. Safe to call often. */
    fun flush() = scheduleSave()

    private fun scheduleSave() {
        if (!savePending.compareAndSet(false, true)) return
        writer.execute {
            savePending.set(false)
            val snapshot = synchronized(lock) { entries.values.toList() }
            write(snapshot)
        }
    }

    private fun write(snapshot: List<Entry>) {
        try {
            file.parentFile?.mkdirs()
            val text = buildString {
                for (entry in snapshot) {
                    append(escape(entry.reading)).append('\t')
                    append(escape(entry.value)).append('\t')
                    append(entry.count).append('\t')
                    append(entry.lastUsed).append('\n')
                }
            }
            file.writeText(text)
        } catch (e: Exception) {
            // History is a convenience; never let a failed write disturb input.
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        try {
            if (!file.isFile) return
            for (line in file.readLines()) {
                val parts = line.split('\t')
                if (parts.size != 4) continue
                val reading = unescape(parts[0])
                val value = unescape(parts[1])
                val count = parts[2].toIntOrNull() ?: continue
                val lastUsed = parts[3].toLongOrNull() ?: continue
                if (reading.isEmpty() || value.isEmpty()) continue
                entries[keyOf(reading, value)] = Entry(reading, value, count, lastUsed)
            }
            trimLocked()
        } catch (e: Exception) {
            // A corrupt history file must not break the keyboard; start empty.
            entries.clear()
        }
    }

    private fun trimLocked() {
        val excess = entries.size - MAX_ENTRIES
        if (excess <= 0) return
        val weakest = entries.values
            .sortedWith(compareBy<Entry> { it.count }.thenBy { it.lastUsed })
            .take(excess)
        for (entry in weakest) entries.remove(keyOf(entry.reading, entry.value))
    }

    private fun keyOf(reading: String, value: String): String = reading + "\u0000" + value

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    companion object {
        const val MAX_ENTRIES = 1000
        const val MAX_SUGGESTIONS = 3
        private const val MIN_READING_LENGTH = 2
        private const val MIN_VALUE_LENGTH = 2
    }
}
