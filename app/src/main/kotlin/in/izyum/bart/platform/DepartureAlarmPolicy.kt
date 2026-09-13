package `in`.izyum.bart.platform

/** Deterministic alarm decisions shared by Android scheduling and tests. */
object DepartureAlarmPolicy {
    const val MIN_POLLING_DELAY_MILLIS = 30_000L
    const val MAX_POLLING_DELAY_MILLIS = 30 * 60_000L
    const val POLLING_SAFETY_MARGIN_MILLIS = 2 * 60_000L

    fun shouldRestore(pending: Boolean, departed: Boolean, expired: Boolean): Boolean =
        pending && !departed && !expired

    fun alarmTime(departureEstimateMillis: Long, leadTimeMinutes: Int): Long =
        departureEstimateMillis - leadTimeMinutes * 60_000L

    fun secondsUntilAlarm(departureEstimateMillis: Long,
                          leadTimeMinutes: Int,
                          nowMillis: Long): Int =
        ((alarmTime(departureEstimateMillis, leadTimeMinutes) - nowMillis) / 1_000L)
            .toInt()

    /**
     * Returns the delay before the next background refresh. Refreshes happen
     * halfway through the remaining time, with a safety margin near the alarm.
     */
    fun nextPollingDelayMillis(timeUntilAlarmMillis: Long): Long {
        val remaining = timeUntilAlarmMillis.coerceAtLeast(0L)
        val halfRemaining = (remaining - POLLING_SAFETY_MARGIN_MILLIS) / 2L
        return halfRemaining.coerceIn(
            MIN_POLLING_DELAY_MILLIS,
            MAX_POLLING_DELAY_MILLIS,
        )
    }
}
