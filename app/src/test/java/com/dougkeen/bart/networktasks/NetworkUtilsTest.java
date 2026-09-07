package com.dougkeen.bart.networktasks;

import static org.junit.Assert.assertEquals;

import okhttp3.OkHttpClient;

import org.junit.Test;

public class NetworkUtilsTest {
    @Test
    public void clientUsesBoundedTimeouts() {
        OkHttpClient client = NetworkUtils.makeHttpClient();

        assertEquals(NetworkUtils.CONNECTION_TIMEOUT_MILLIS,
                client.connectTimeoutMillis());
        assertEquals(NetworkUtils.READ_TIMEOUT_MILLIS,
                client.readTimeoutMillis());
        assertEquals(NetworkUtils.WRITE_TIMEOUT_MILLIS,
                client.writeTimeoutMillis());
        assertEquals(NetworkUtils.CALL_TIMEOUT_MILLIS,
                client.callTimeoutMillis());
    }
}
