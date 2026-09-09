package `in`.izyum.bart.networktasks

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkUtils {
    @JvmStatic
    fun makeHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECTION_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    @JvmField
    val CONNECTION_TIMEOUT_MILLIS = 10000

    @JvmField
    val READ_TIMEOUT_MILLIS = 15000

    @JvmField
    val WRITE_TIMEOUT_MILLIS = 15000

    @JvmField
    val CALL_TIMEOUT_MILLIS = 20000
}
