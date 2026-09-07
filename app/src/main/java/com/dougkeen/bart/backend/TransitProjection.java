package com.dougkeen.bart.backend;

import java.util.Objects;

/** Derives a consumer-facing value from one complete feed snapshot. */
public interface TransitProjection<T> {
    T project(TransitFeedSnapshot snapshot) throws Exception;

    /** Allows projections to suppress updates caused by unrelated feed data. */
    default boolean areEquivalent(T previous, T current) {
        return Objects.equals(previous, current);
    }
}
