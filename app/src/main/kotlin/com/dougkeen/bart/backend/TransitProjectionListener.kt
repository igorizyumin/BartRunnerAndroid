package com.dougkeen.bart.backend

interface TransitProjectionListener<T> {
    fun onData(value: T, snapshot: TransitFeedSnapshot)

    fun onError(exception: Exception, lastSnapshot: TransitFeedSnapshot?)
}
