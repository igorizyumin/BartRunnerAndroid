package `in`.izyum.bart.networktasks

import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.model.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class EtdLookup(
    val board: EtdStationBoard?,
    val error: Exception? = null,
)

/** Shares station ETD responses and expires them at the next reported train. */
class EtdStationCache(
    private val client: EtdClient,
    private val timeSource: TimeSource = SystemTimeSource,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    private data class Entry(
        val lookup: EtdLookup,
        val validUntilMillis: Long,
    )

    private val lock = Any()
    private val entries = mutableMapOf<Station, Entry>()
    private val stationLocks = mutableMapOf<Station, Any>()

    fun get(station: Station): EtdLookup {
        val stationLock = synchronized(lock) {
            stationLocks.getOrPut(station) { Any() }
        }
        synchronized(stationLock) {
            val now = timeSource.nowMillis()
            synchronized(lock) {
                entries[station]
                    ?.takeIf { now < it.validUntilMillis }
                    ?.let { return it.lookup }
            }
            val lookup = try {
                EtdLookup(client.fetch(station, now))
            } catch (exception: Exception) {
                EtdLookup(null, exception)
            }
            val nextDeparture = lookup.board?.departures
                ?.map { it.departureTimeMillis }
                ?.filter { it > now }
                ?.minOrNull()
            val validUntil = nextDeparture ?: now + retryMillis
            synchronized(lock) {
                entries[station] = Entry(lookup, validUntil)
            }
            return lookup
        }
    }

    suspend fun getAll(stations: Set<Station>): Map<Station, EtdLookup> = coroutineScope {
        stations.map { station ->
            async(Dispatchers.IO) { station to get(station) }
        }.awaitAll().toMap()
    }

    fun clear() = synchronized(lock) { entries.clear() }

    companion object {
        private const val DEFAULT_RETRY_MILLIS = 15_000L
    }
}
