package `in`.izyum.bart.platform

/** Deterministic alarm decisions shared by Android scheduling and tests. */
object DepartureAlarmPolicy {
    fun shouldRestore(pending: Boolean, departed: Boolean, expired: Boolean): Boolean =
        pending && !departed && !expired

    fun alarmTime(departureEstimateMillis: Long, leadTimeMinutes: Int): Long =
        departureEstimateMillis - leadTimeMinutes * 60_000L

    fun secondsUntilAlarm(departureEstimateMillis: Long,
                          leadTimeMinutes: Int,
                          nowMillis: Long): Int =
        ((alarmTime(departureEstimateMillis, leadTimeMinutes) - nowMillis) / 1_000L)
            .toInt()
}
