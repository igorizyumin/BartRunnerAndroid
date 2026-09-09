package `in`.izyum.bart.backend

import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class TransitRepositoryTest {
    private var repository: TransitRepository? = null

    @After
    fun tearDown() {
        repository?.close()
    }

    @Test
    fun feedFetchesInitialSnapshotAndReplaysLatestState() = runBlocking {
        val client = FakeFeedClient()
        val expected = snapshot(1, 10_000L)
        client.enqueue(expected)
        repository = newRepository(client, 60_000L)

        val first = withTimeout(TIMEOUT_MILLIS) {
            repository!!.feed().first { it.snapshot != null }
        }

        assertSame(expected, first.snapshot)
        assertSame(expected, repository!!.state.value.snapshot)
    }

    @Test
    fun flowReportsErrorsAndRetainsLastGoodSnapshot() = runBlocking {
        val client = FakeFeedClient()
        val expected = snapshot(2, 20_000L)
        client.enqueue(expected)
        repository = newRepository(client, 60_000L)

        withTimeout(TIMEOUT_MILLIS) {
            repository!!.feed().first { it.snapshot != null }
        }

        client.enqueue(IOException("network unavailable"))
        repository!!.refreshNow()

        val state = repository!!.state.value
        assertSame(expected, state.snapshot)
        assertEquals("network unavailable", state.error?.message)
    }

    @Test
    fun partialFeedRefreshPublishesMergedSnapshot() = runBlocking {
        val client = FakeFeedClient()
        val first = snapshot(3, 30_000L)
        val updated = snapshot(4, 40_000L)
        client.enqueue(TransitFeedFetchResult.complete(first))
        client.enqueue(
            TransitFeedFetchResult(
                updated.tripUpdates,
                null,
                null,
                IOException("alerts unavailable"),
            ),
        )
        repository = newRepository(client, 60_000L)

        withTimeout(TIMEOUT_MILLIS) {
            repository!!.feed().first { it.snapshot != null }
        }
        repository!!.refreshNow()

        val state = repository!!.state.value
        assertSame(updated.tripUpdates, state.snapshot?.tripUpdates)
        assertSame(first.alerts, state.snapshot?.alerts)
        assertEquals("alerts unavailable", state.error?.message)
    }

    @Test
    fun cancellingLastFlowCollectorStopsPolling() = runBlocking {
        val client = FakeFeedClient(generatedSnapshots = true)
        repository = newRepository(client, 25L)

        val collection = launch {
            repository!!.feed().collect()
        }
        withTimeout(TIMEOUT_MILLIS) {
            while (client.fetchCount.get() < 2) {
                delay(5L)
            }
        }
        collection.cancelAndJoin()
        val countAfterCancel = client.fetchCount.get()
        Thread.sleep(100L)

        assertEquals(countAfterCancel, client.fetchCount.get())
    }

    @Test
    fun pollingWaitsForTheAppToBecomeForeground() = runBlocking {
        val client = FakeFeedClient(generatedSnapshots = true)
        repository = newInactiveRepository(client, 25L)

        val collection = launch {
            repository!!.feed().collect()
        }
        delay(100L)
        assertEquals(0, client.fetchCount.get())

        repository!!.setAppInForeground(true)
        withTimeout(TIMEOUT_MILLIS) {
            while (client.fetchCount.get() < 1) {
                delay(5L)
            }
        }
        collection.cancelAndJoin()
    }

    @Test
    fun backgroundPollingMayContinueWhenAnAlarmIsPending() = runBlocking {
        val client = FakeFeedClient(generatedSnapshots = true)
        val alarmPending = MutableStateFlow(false)
        repository = TransitRepository(client, 25L, alarmPending)

        val collection = launch {
            repository!!.feed().collect()
        }
        delay(100L)
        assertEquals(0, client.fetchCount.get())

        alarmPending.value = true
        withTimeout(TIMEOUT_MILLIS) {
            while (client.fetchCount.get() < 1) {
                delay(5L)
            }
        }
        collection.cancelAndJoin()
    }

    @Test
    fun pollingStopsWhenTheAppLeavesTheForegroundWithoutAnAlarm() = runBlocking {
        val client = FakeFeedClient(generatedSnapshots = true)
        repository = newRepository(client, 25L)

        val collection = launch {
            repository!!.feed().collect()
        }
        withTimeout(TIMEOUT_MILLIS) {
            while (client.fetchCount.get() < 1) {
                delay(5L)
            }
        }
        repository!!.setAppInForeground(false)
        val countAfterStop = client.fetchCount.get()
        delay(100L)

        assertEquals(countAfterStop, client.fetchCount.get())
        collection.cancelAndJoin()
    }

    @Test
    fun restartingFlowWithinRefreshIntervalReusesLatestSnapshot() = runBlocking {
        val client = FakeFeedClient(generatedSnapshots = true)
        repository = newRepository(client, 60_000L)

        val firstCollection = launch {
            repository!!.feed().collect()
        }
        withTimeout(TIMEOUT_MILLIS) {
            while (client.fetchCount.get() < 1) {
                delay(5L)
            }
        }
        firstCollection.cancelAndJoin()

        val secondCollection = launch {
            repository!!.feed().collect()
        }
        delay(100L)

        assertEquals(1, client.fetchCount.get())
        secondCollection.cancelAndJoin()
    }

    @Test
    fun pollingContinuesUntilTheLastFlowCollectorIsCancelled() = runBlocking {
        val client = FakeFeedClient(generatedSnapshots = true)
        repository = newRepository(client, 25L)

        val firstCollection = launch {
            repository!!.feed().collect()
        }
        withTimeout(TIMEOUT_MILLIS) {
            while (client.fetchCount.get() < 2) {
                delay(5L)
            }
        }

        val secondCollection = launch {
            repository!!.feed().collect()
        }
        yield()
        firstCollection.cancelAndJoin()
        val countAfterFirstCancel = client.fetchCount.get()
        withTimeout(TIMEOUT_MILLIS) {
            while (client.fetchCount.get() <= countAfterFirstCancel) {
                delay(5L)
            }
        }

        secondCollection.cancelAndJoin()
        val countAfterLastCancel = client.fetchCount.get()
        Thread.sleep(100L)

        assertEquals(countAfterLastCancel, client.fetchCount.get())
    }

    @Test
    fun projectedFlowMapsTheSharedSnapshot() = runBlocking {
        val client = FakeFeedClient()
        client.enqueue(snapshot(5, 50_000L))
        repository = newRepository(client, 60_000L)

        val result = withTimeout(TIMEOUT_MILLIS) {
            repository!!.projectedState(project = { snapshot ->
                snapshot.tripUpdates.entityCount
            }).first { it.getOrNull() != null }
        }

        assertEquals(1, result.getOrNull())
    }

    private fun newRepository(client: TransitFeedClient, intervalMillis: Long) =
        newInactiveRepository(client, intervalMillis).also {
            it.setAppInForeground(true)
        }

    private fun newInactiveRepository(client: TransitFeedClient, intervalMillis: Long) =
        TransitRepository(client, intervalMillis)

    private fun snapshot(id: Long, timestampMillis: Long): TransitFeedSnapshot {
        val header = GtfsRealtime.FeedHeader.newBuilder()
            .setGtfsRealtimeVersion("2.0")
            .setTimestamp(timestampMillis / 1000L)
            .build()
        val tripUpdates = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(header)
            .addEntity(
                GtfsRealtime.FeedEntity.newBuilder()
                    .setId("trip-$id")
                    .build(),
            )
            .build()
        val alerts = GtfsRealtime.FeedMessage.newBuilder()
            .setHeader(header)
            .build()
        return TransitFeedSnapshot(tripUpdates, alerts, timestampMillis)
    }

    private class FakeFeedClient(
        private val generatedSnapshots: Boolean = false,
    ) : TransitFeedClient {
        private val responses = ArrayDeque<Any>()
        val fetchCount = AtomicInteger()

        fun enqueue(response: Any) {
            responses.add(response)
        }

        override fun fetchFeeds(): TransitFeedFetchResult {
            fetchCount.incrementAndGet()
            if (generatedSnapshots) {
                val count = fetchCount.get().toLong()
                return TransitFeedFetchResult.complete(snapshotForFetch(count))
            }
            val response = responses.removeFirst()
            return when (response) {
                is TransitFeedFetchResult -> response
                is IOException -> TransitFeedFetchResult.failed(response)
                else -> TransitFeedFetchResult.complete(response as TransitFeedSnapshot)
            }
        }

        private fun snapshotForFetch(id: Long): TransitFeedSnapshot {
            val header = GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0")
                .setTimestamp(id)
                .build()
            val tripUpdates = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(header)
                .addEntity(
                    GtfsRealtime.FeedEntity.newBuilder()
                        .setId("trip-$id")
                        .build(),
                )
                .build()
            val alerts = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(header)
                .build()
            return TransitFeedSnapshot(tripUpdates, alerts, id * 1_000L)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 2_000L
    }
}
