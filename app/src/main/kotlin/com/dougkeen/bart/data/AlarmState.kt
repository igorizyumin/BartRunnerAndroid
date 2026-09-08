package com.dougkeen.bart.data

/** Immutable alarm state exposed to UI consumers. */
data class AlarmState(
    val ringtoneRequested: Boolean = false,
    val sounding: Boolean = false,
)
