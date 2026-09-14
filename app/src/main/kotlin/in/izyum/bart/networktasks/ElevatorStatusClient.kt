package `in`.izyum.bart.networktasks

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Fetches the current elevator advisory from BART's legacy API. */
class ElevatorStatusClient @JvmOverloads constructor(
    private val client: OkHttpClient = NetworkUtils.makeHttpClient(),
) {
    fun fetchDescription(): String {
        val request = Request.Builder()
            .url(elevatorUrl())
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Elevator status returned ${response.code}")
            }
            val body = response.body
                ?: throw IOException("Elevator status returned an empty body")
            parseDescription(body.string()).ifBlank {
                throw IOException("Elevator status response did not contain a description")
            }
        }
    }

    private fun elevatorUrl(): HttpUrl = BartApiConfig.LEGACY_ELEVATOR_URL.toHttpUrl()
        .newBuilder()
        .addQueryParameter("cmd", "elev")
        .addQueryParameter("key", BartApiConfig.LEGACY_API_KEY)
        .addQueryParameter("json", "y")
        .build()

    companion object {
        private val objectMapper = ObjectMapper()

        internal fun parseDescription(json: String): String {
            val bsa = objectMapper.readTree(json).path("root").path("bsa")
            val advisories = if (bsa.isArray) bsa.toList() else listOf(bsa)
            return advisories
                .mapNotNull { advisory ->
                    val description = advisory.path("description")
                    val cdata = description.path("#cdata-section")
                    when {
                        cdata.isTextual -> cdata.asText()
                        description.isTextual -> description.asText()
                        else -> null
                    }
                }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString("\n\n")
        }
    }
}
