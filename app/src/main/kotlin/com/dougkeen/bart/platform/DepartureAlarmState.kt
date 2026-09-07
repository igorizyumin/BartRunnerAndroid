package com.dougkeen.bart.platform

/** Immutable alarm state exposed to lifecycle-aware consumers. */
data class DepartureAlarmState(
    val leadTimeMinutes: Int = 0,
    val pending: Boolean = false,
)
