package com.dougkeen.bart.backend;

public interface TransitProjectionListener<T> {
    void onData(T value, TransitFeedSnapshot snapshot);

    void onError(Exception exception, TransitFeedSnapshot lastSnapshot);
}
