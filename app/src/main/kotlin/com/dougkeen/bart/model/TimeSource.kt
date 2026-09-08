package com.dougkeen.bart.model

/** Small seam for deterministic time-dependent transit decisions. */
fun interface TimeSource {
    fun nowMillis(): Long
}

object SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
