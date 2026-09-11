package `in`.izyum.bart.networktasks

import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.Station
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** One legacy ETD estimate returned by BART's station operations API. */
data class EtdDeparture(
    val destination: Station?,
    val line: Line?,
    val departureTimeMillis: Long,
    val platform: String?,
    val direction: String?,
    val canceled: Boolean,
)

/** A normalized legacy ETD board for one station. */
data class EtdStationBoard(
    val station: Station,
    val receivedAtMillis: Long,
    val departures: List<EtdDeparture>,
) {
    fun departuresFor(line: Line?, destination: Station?): List<EtdDeparture> =
        departures.filter { departure ->
            normalizeLine(departure.line) == normalizeLine(line)
                && departure.destination == destination
        }

    private fun normalizeLine(line: Line?): Line? = when (line) {
        Line.YELLOW_LATE_NIGHT -> Line.YELLOW
        else -> line
    }
}

interface EtdClient {
    fun fetch(station: Station, receivedAtMillis: Long): EtdStationBoard
}

/** Downloads the legacy ETD board used by BART's station displays. */
class HttpEtdClient @JvmOverloads constructor(
    private val client: OkHttpClient = NetworkUtils.makeHttpClient(),
) : EtdClient {
    override fun fetch(station: Station, receivedAtMillis: Long): EtdStationBoard {
        val request = Request.Builder()
            .url(etdUrl(station))
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ETD status returned ${response.code}")
            }
            val body = response.body
                ?: throw IOException("ETD status returned an empty body")
            parseBoard(station, receivedAtMillis, body.string())
        }
    }

    private fun etdUrl(station: Station): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("api.bart.gov")
        .addPathSegment("api")
        .addPathSegment("etd.aspx")
        .addQueryParameter("cmd", "etd")
        .addQueryParameter("orig", station.abbreviation.lowercase())
        .addQueryParameter("key", API_KEY)
        .addQueryParameter("json", "y")
        .build()

    companion object {
        private const val API_KEY = "MW9S-E7SL-26DU-VV8V"
        private val objectMapper = ObjectMapper()

        internal fun parseBoard(
            station: Station,
            receivedAtMillis: Long,
            json: String,
        ): EtdStationBoard {
            val root = objectMapper.readTree(json).path("root")
            val stationNode = asList(root.path("station")).firstOrNull()
                ?: throw IOException("ETD response did not contain a station")
            val departures = asList(stationNode.path("etd")).flatMap { etd ->
                val destination = Station.getByAbbreviation(
                    text(etd.path("abbreviation"))
                )
                val line = lineForColor(text(etd.path("color")))
                asList(etd.path("estimate")).mapNotNull { estimate ->
                    val minutes = parseMinutes(text(estimate.path("minutes")))
                        ?: return@mapNotNull null
                    EtdDeparture(
                        destination = destination,
                        line = line,
                        departureTimeMillis = receivedAtMillis + minutes * 60_000L,
                        platform = text(estimate.path("platform")),
                        direction = text(estimate.path("direction")),
                        canceled = text(estimate.path("cancelflag")) == "1",
                    )
                }
            }
            return EtdStationBoard(station, receivedAtMillis, departures)
        }

        private fun asList(node: JsonNode): List<JsonNode> = when {
            node.isArray -> node.toList()
            node.isObject -> listOf(node)
            else -> emptyList()
        }

        private fun text(node: JsonNode): String? = when {
            node.isTextual -> node.asText().trim().takeIf(String::isNotEmpty)
            node.isObject && node.path("#cdata-section").isTextual ->
                node.path("#cdata-section").asText().trim().takeIf(String::isNotEmpty)
            else -> null
        }

        private fun parseMinutes(value: String?): Long? = when {
            value == null -> null
            value.equals("leaving", ignoreCase = true) -> 0L
            else -> value.toLongOrNull()?.coerceAtLeast(0L)
        }

        private fun lineForColor(color: String?): Line? = when (color?.uppercase()) {
            "RED" -> Line.RED
            "ORANGE" -> Line.ORANGE
            "YELLOW" -> Line.YELLOW
            "BLUE" -> Line.BLUE
            "GREEN" -> Line.GREEN
            else -> null
        }
    }
}
