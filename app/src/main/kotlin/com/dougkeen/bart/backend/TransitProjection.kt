package com.dougkeen.bart.backend

import java.util.Objects
import kotlin.jvm.JvmDefaultWithCompatibility

/** Derives a consumer-facing value from one complete feed snapshot. */
@JvmDefaultWithCompatibility
interface TransitProjection<T> {
    @Throws(Exception::class)
    fun project(snapshot: TransitFeedSnapshot): T

    /** Allows projections to suppress updates caused by unrelated feed data. */
    fun areEquivalent(previous: T?, current: T?): Boolean =
        Objects.equals(previous, current)
}
