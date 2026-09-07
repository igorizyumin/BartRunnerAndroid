package com.dougkeen.bart.backend;

import java.io.IOException;

/** Fetches the complete realtime input used by the transit backend. */
public interface TransitFeedClient {
    TransitFeedSnapshot fetch() throws IOException;

    /**
     * Fetches the two realtime feeds independently. Implementations that
     * already have a combined endpoint may keep the compatibility default;
     * network clients should override this to preserve partial successes.
     */
    default TransitFeedFetchResult fetchFeeds() {
        try {
            return TransitFeedFetchResult.complete(fetch());
        } catch (Exception exception) {
            return TransitFeedFetchResult.failed(exception);
        }
    }
}
