package com.dougkeen.bart.model

/** Stable app identities for the BART lines described by static GTFS. */
enum class Line(
    val transferLine1: Line? = null,
    val transferLine2: Line? = null
) {
    RED,
    ORANGE,
    YELLOW,
    YELLOW_LATE_NIGHT,
    BLUE,
    GREEN,
    YELLOW_ORANGE_SCHEDULED_TRANSFER(YELLOW, ORANGE),
    PURPLE;

    fun requiresTransfer(): Boolean = transferLine1 != null
}
