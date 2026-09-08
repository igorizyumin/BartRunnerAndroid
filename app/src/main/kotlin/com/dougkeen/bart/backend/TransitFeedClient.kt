package com.dougkeen.bart.backend

import java.io.IOException

/** Fetches the independent realtime feeds used by the transit backend. */
interface TransitFeedClient {
    @Throws(IOException::class)
    fun fetchFeeds(): TransitFeedFetchResult
}
