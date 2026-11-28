package org.mozkey.inputmethod.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counters that show how far a keystroke gets through the conversion pipeline.
 * Shown on the settings screen to tell "live conversion never starts" apart from
 * "Zenz is never asked" and "Zenz answers but its result is not adopted".
 * Holds numbers and flags only, never any typed text.
 */
object Diagnostics {
    val keysSent = AtomicInteger()
    val liveConversionCallbacks = AtomicInteger()
    val zenzCallbacks = AtomicInteger()
    val zenzPendingOutputs = AtomicInteger()
    val zenzAppliedOutputs = AtomicInteger()

    @Volatile var engineReady = false
    @Volatile var configLiveConversion = false
    @Volatile var configZenz = false
    @Volatile var configZenzTimeoutMsec = 0
}
