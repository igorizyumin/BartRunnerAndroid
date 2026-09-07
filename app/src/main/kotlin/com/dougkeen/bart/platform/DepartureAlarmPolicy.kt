package com.dougkeen.bart.platform

/** Deterministic alarm decisions shared by Android scheduling and tests. */
object DepartureAlarmPolicy {
    fun shouldRestore(pending: Boolean, departed: Boolean, expired: Boolean): Boolean =
        pending && !departed && !expired

    fun alarmTime(meanEstimateMillis: Long, leadTimeMinutes: Int): Long =
        meanEstimateMillis - leadTimeMinutes * 60_000L

    fun secondsUntilAlarm(meanEstimateMillis: Long,
                          leadTimeMinutes: Int,
                          nowMillis: Long): Int =
        ((alarmTime(meanEstimateMillis, leadTimeMinutes) - nowMillis) / 1_000L)
            .toInt()
}
