package com.dougkeen.bart.backend

import java.io.IOException
import kotlin.jvm.JvmDefaultWithCompatibility

/** Fetches the complete realtime input used by the transit backend. */
@JvmDefaultWithCompatibility
interface TransitFeedClient {
    @Throws(IOException::class)
    fun fetch(): TransitFeedSnapshot

    /**
     * Fetches the two realtime feeds independently. Implementations that
     * already have a combined endpoint may keep the compatibility default;
     * network clients should override this to preserve partial successes.
     */
    fun fetchFeeds(): TransitFeedFetchResult = try {
        TransitFeedFetchResult.complete(fetch())
    } catch (exception: Exception) {
        TransitFeedFetchResult.failed(exception)
    }
}
