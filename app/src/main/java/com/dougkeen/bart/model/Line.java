package com.dougkeen.bart.model;

/** Stable app identities for the BART lines described by static GTFS. */
public enum Line {
    RED,
    ORANGE,
    YELLOW,
    YELLOW_LATE_NIGHT,
    BLUE,
    GREEN,
    YELLOW_ORANGE_SCHEDULED_TRANSFER(YELLOW, ORANGE),
    PURPLE;

    private final boolean requiresTransfer;
    final Line transferLine1;
    final Line transferLine2;

    Line() {
        requiresTransfer = false;
        this.transferLine1 = null;
        this.transferLine2 = null;
    }

    Line(Line transferLine1, Line transferLine2) {
        this.requiresTransfer = true;
        this.transferLine1 = transferLine1;
        this.transferLine2 = transferLine2;
    }

    public boolean requiresTransfer() {
        return requiresTransfer;
    }

}
