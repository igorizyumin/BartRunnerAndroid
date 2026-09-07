package com.dougkeen.bart.backend;

import java.io.IOException;

/** Fetches the complete realtime input used by the transit backend. */
public interface TransitFeedClient {
    TransitFeedSnapshot fetch() throws IOException;
}
