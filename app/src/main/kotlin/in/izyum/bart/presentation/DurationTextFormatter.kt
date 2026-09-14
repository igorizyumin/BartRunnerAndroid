package `in`.izyum.bart.presentation

import java.util.Locale

/** Formats countdown durations for display. */
object DurationTextFormatter {
    fun clock(seconds: Long): String {
        val safeSeconds = seconds.coerceAtLeast(0L)
        val hours = safeSeconds / SECONDS_PER_HOUR
        val minutes = (safeSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val remainingSeconds = safeSeconds % SECONDS_PER_MINUTE
        return if (hours > 0L) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, remainingSeconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, remainingSeconds)
        }
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
}
