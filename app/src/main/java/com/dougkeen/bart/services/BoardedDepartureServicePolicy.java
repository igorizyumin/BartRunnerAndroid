package com.dougkeen.bart.services;

/** Pure polling decisions used by the boarded-departure service. */
public final class BoardedDepartureServicePolicy {
    private static final int FAST_POLL_MILLIS = 6 * 1000;
    private static final int SLOW_POLL_MILLIS = 15 * 1000;
    private static final int FAST_POLL_THRESHOLD_SECONDS = 3 * 60;

    private BoardedDepartureServicePolicy() {
    }

    public static boolean shouldStop(boolean hasDeparture, boolean hasDeparted) {
        return !hasDeparture || hasDeparted;
    }

    public static int pollIntervalMillis(int secondsUntilAlarm) {
        return secondsUntilAlarm > FAST_POLL_THRESHOLD_SECONDS
                ? SLOW_POLL_MILLIS : FAST_POLL_MILLIS;
    }
}
