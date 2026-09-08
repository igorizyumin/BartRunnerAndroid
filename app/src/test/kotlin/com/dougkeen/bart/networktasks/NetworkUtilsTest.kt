package com.dougkeen.bart.networktasks

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkUtilsTest {
    @Test
    fun clientUsesBoundedTimeouts() {
        val client: OkHttpClient = NetworkUtils.makeHttpClient()
        assertEquals(NetworkUtils.CONNECTION_TIMEOUT_MILLIS, client.connectTimeoutMillis)
        assertEquals(NetworkUtils.READ_TIMEOUT_MILLIS, client.readTimeoutMillis)
        assertEquals(NetworkUtils.WRITE_TIMEOUT_MILLIS, client.writeTimeoutMillis)
        assertEquals(NetworkUtils.CALL_TIMEOUT_MILLIS, client.callTimeoutMillis)
    }
}
