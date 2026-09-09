package `in`.izyum.bart.performance

import android.os.Trace

/** Lightweight tracing that is inactive unless Android system tracing is enabled. */
internal object PerformanceTrace {
    fun <T> section(name: String, block: () -> T): T {
        if (!isEnabled()) {
            return block()
        }
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    suspend fun <T> suspendSection(
        name: String,
        block: suspend () -> T,
    ): T {
        if (!isEnabled()) {
            return block()
        }
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    fun instant(name: String) {
        if (isEnabled()) {
            Trace.beginSection(name)
            Trace.endSection()
        }
    }

    fun counter(name: String, value: Int) {
        if (isEnabled()) {
            Trace.setCounter(name, value.toLong())
        }
    }

    private fun isEnabled(): Boolean = try {
        Trace.isEnabled()
    } catch (_: RuntimeException) {
        // Local JVM tests use Android stubs that do not implement Trace.
        false
    }
}
