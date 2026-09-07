package com.dougkeen.bart.networktasks;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class NetworkUtils {

    public static OkHttpClient makeHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .build();
    }

    static final int CONNECTION_TIMEOUT_MILLIS = 10000;
    static final int READ_TIMEOUT_MILLIS = 15000;
    static final int WRITE_TIMEOUT_MILLIS = 15000;
    static final int CALL_TIMEOUT_MILLIS = 20000;
}
