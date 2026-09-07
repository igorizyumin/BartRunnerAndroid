package com.dougkeen.bart.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.google.transit.realtime.GtfsRealtime;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class TransitRepositoryTest {
    private static final long TIMEOUT_SECONDS = 2L;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private TransitRepository repository;

    @After
    public void tearDown() {
        if (repository != null) {
            repository.close();
        }
        scheduler.shutdownNow();
    }

    @Test
    public void publishesInitialSnapshotAndReplaysItToNewSubscribers()
            throws Exception {
        FakeFeedClient client = new FakeFeedClient();
        TransitFeedSnapshot expected = snapshot(1, 10_000L);
        client.enqueue(expected);
        repository = newRepository(client, 60_000L);

        CountDownLatch firstLatch = new CountDownLatch(1);
        AtomicReference<TransitFeedSnapshot> firstValue =
                new AtomicReference<TransitFeedSnapshot>();
        TransitRepository.Subscription first = repository.subscribe(
                listener(firstLatch, firstValue, null));
        await(firstLatch);
        assertSame(expected, firstValue.get());

        CountDownLatch replayLatch = new CountDownLatch(1);
        AtomicReference<TransitFeedSnapshot> replayValue =
                new AtomicReference<TransitFeedSnapshot>();
        TransitRepository.Subscription second = repository.subscribe(
                listener(replayLatch, replayValue, null));
        await(replayLatch);
        assertSame(expected, replayValue.get());
        assertEquals(1, client.getFetchCount());

        first.close();
        second.close();
    }

    @Test
    public void doesNotPublishWhenFeedDataIsUnchanged() throws Exception {
        FakeFeedClient client = new FakeFeedClient();
        TransitFeedSnapshot first = snapshot(2, 20_000L);
        TransitFeedSnapshot sameFeedLater = new TransitFeedSnapshot(
                first.getTripUpdates(), first.getAlerts(), 30_000L);
        client.enqueue(first);
        client.enqueue(sameFeedLater);
        repository = newRepository(client, 60_000L);

        AtomicInteger snapshotCount = new AtomicInteger();
        CountDownLatch initialLatch = new CountDownLatch(1);
        TransitRepository.Subscription subscription = repository.subscribe(
                listener(initialLatch, null, snapshotCount));
        await(initialLatch);

        repository.refreshNow();

        assertEquals(1, snapshotCount.get());
        assertSame(first, repository.getLatestSnapshot());
        subscription.close();
    }

    @Test
    public void reportsErrorsAndRetainsLastGoodSnapshot() throws Exception {
        FakeFeedClient client = new FakeFeedClient();
        TransitFeedSnapshot expected = snapshot(3, 30_000L);
        client.enqueue(expected);
        client.enqueue(new IOException("network unavailable"));
        repository = newRepository(client, 60_000L);

        CountDownLatch snapshotLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();
        TransitRepository.Listener listener = new TransitRepository.Listener() {
            @Override
            public void onSnapshot(TransitFeedSnapshot snapshot) {
                snapshotLatch.countDown();
            }

            @Override
            public void onError(Exception exception,
                                TransitFeedSnapshot lastSnapshot) {
                error.set(exception);
                assertSame(expected, lastSnapshot);
                errorLatch.countDown();
            }
        };

        TransitRepository.Subscription subscription = repository.subscribe(listener);
        await(snapshotLatch);
        repository.refreshNow();
        await(errorLatch);

        assertNotNull(error.get());
        assertEquals("network unavailable", error.get().getMessage());
        assertSame(expected, repository.getLatestSnapshot());
        subscription.close();
    }

    @Test
    public void publishesOneFeedWhenTheOtherFeedFails() throws Exception {
        PartialFeedClient client = new PartialFeedClient();
        TransitFeedSnapshot first = snapshot(5, 50_000L);
        TransitFeedSnapshot updated = new TransitFeedSnapshot(
                snapshot(6, 60_000L).getTripUpdates(), first.getAlerts(),
                60_000L);
        client.enqueue(TransitFeedFetchResult.complete(first));
        client.enqueue(new TransitFeedFetchResult(updated.getTripUpdates(), null,
                null, new IOException("alerts unavailable")));
        repository = newRepository(client, 60_000L);

        CountDownLatch initialLatch = new CountDownLatch(1);
        CountDownLatch updateLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<Exception>();
        AtomicReference<TransitFeedSnapshot> latest =
                new AtomicReference<TransitFeedSnapshot>();
        TransitRepository.Subscription subscription = repository.subscribe(
                new TransitRepository.Listener() {
                    @Override
                    public void onSnapshot(TransitFeedSnapshot snapshot) {
                        latest.set(snapshot);
                        if (snapshot.getTripUpdates()
                                .equals(first.getTripUpdates())) {
                            initialLatch.countDown();
                        }
                        if (snapshot.getTripUpdates()
                                .equals(updated.getTripUpdates())) {
                            updateLatch.countDown();
                        }
                    }

                    @Override
                    public void onError(Exception exception,
                                        TransitFeedSnapshot lastSnapshot) {
                        error.set(exception);
                        assertSame(updated.getAlerts(),
                                lastSnapshot.getAlerts());
                        errorLatch.countDown();
                    }
                });
        await(initialLatch);

        repository.refreshNow();

        await(updateLatch);
        await(errorLatch);
        assertEquals("alerts unavailable", error.get().getMessage());
        assertSame(updated.getTripUpdates(), latest.get().getTripUpdates());
        assertSame(first.getAlerts(), latest.get().getAlerts());
        subscription.close();
    }

    @Test
    public void stopsPollingWhenLastSubscriberCloses() throws Exception {
        FakeFeedClient client = new FakeFeedClient();
        client.setGeneratedSnapshot(true);
        repository = newRepository(client, 25L);

        CountDownLatch firstLatch = new CountDownLatch(1);
        TransitRepository.Subscription subscription = repository.subscribe(
                listener(firstLatch, null, null));
        await(firstLatch);
        waitForFetchCount(client, 2);
        subscription.close();
        int countAfterClose = client.getFetchCount();
        Thread.sleep(100L);

        assertEquals(countAfterClose, client.getFetchCount());
    }

    @Test
    public void projectedSubscriptionSuppressesEquivalentResults()
            throws Exception {
        FakeFeedClient client = new FakeFeedClient();
        TransitFeedSnapshot first = snapshot(4, 40_000L);
        TransitFeedSnapshot sameFeedLater = new TransitFeedSnapshot(
                first.getTripUpdates(), first.getAlerts(), 50_000L);
        client.enqueue(first);
        client.enqueue(sameFeedLater);
        repository = newRepository(client, 60_000L);

        CountDownLatch dataLatch = new CountDownLatch(1);
        AtomicInteger dataCount = new AtomicInteger();
        TransitRepository.Subscription subscription = repository.subscribe(
                snapshot -> snapshot.getTripUpdates().getEntityCount(),
                new TransitProjectionListener<Integer>() {
                    @Override
                    public void onData(Integer value,
                                       TransitFeedSnapshot snapshot) {
                        dataCount.incrementAndGet();
                        dataLatch.countDown();
                    }

                    @Override
                    public void onError(Exception exception,
                                        TransitFeedSnapshot lastSnapshot) {
                    }
                });
        await(dataLatch);

        repository.refreshNow();

        assertEquals(1, dataCount.get());
        subscription.close();
    }

    private TransitRepository newRepository(TransitFeedClient client,
                                            long intervalMillis) {
        return new TransitRepository(client, scheduler, Runnable::run,
                intervalMillis);
    }

    private TransitRepository.Listener listener(
            CountDownLatch latch,
            AtomicReference<TransitFeedSnapshot> value,
            AtomicInteger count) {
        return new TransitRepository.Listener() {
            @Override
            public void onSnapshot(TransitFeedSnapshot snapshot) {
                if (value != null) {
                    value.set(snapshot);
                }
                if (count != null) {
                    count.incrementAndGet();
                }
                latch.countDown();
            }

            @Override
            public void onError(Exception exception,
                                TransitFeedSnapshot lastSnapshot) {
                latch.countDown();
            }
        };
    }

    private static TransitFeedSnapshot snapshot(long id, long timestampMillis) {
        GtfsRealtime.FeedHeader header = GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0")
                .setTimestamp(timestampMillis / 1000L)
                .build();
        GtfsRealtime.FeedEntity entity = GtfsRealtime.FeedEntity.newBuilder()
                .setId("trip-" + id)
                .build();
        GtfsRealtime.FeedMessage tripUpdates = GtfsRealtime.FeedMessage
                .newBuilder()
                .setHeader(header)
                .addEntity(entity)
                .build();
        GtfsRealtime.FeedMessage alerts = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(header)
                .build();
        return new TransitFeedSnapshot(tripUpdates, alerts, timestampMillis);
    }

    private static void await(CountDownLatch latch) throws Exception {
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for repository callback");
        }
    }

    private static void waitForFetchCount(FakeFeedClient client,
                                          int expected) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (client.getFetchCount() < expected
                && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(expected, client.getFetchCount());
    }

    private static final class FakeFeedClient implements TransitFeedClient {
        private final Queue<Object> responses = new ArrayDeque<Object>();
        private final AtomicInteger fetchCount = new AtomicInteger();
        private boolean generatedSnapshot;

        void enqueue(Object response) {
            responses.add(response);
        }

        void setGeneratedSnapshot(boolean generatedSnapshot) {
            this.generatedSnapshot = generatedSnapshot;
        }

        int getFetchCount() {
            return fetchCount.get();
        }

        @Override
        public TransitFeedSnapshot fetch() throws IOException {
            fetchCount.incrementAndGet();
            if (generatedSnapshot) {
                long count = fetchCount.get();
                return snapshot(count, count * 1_000L);
            }
            Object response = responses.remove();
            if (response instanceof IOException) {
                throw (IOException) response;
            }
            return (TransitFeedSnapshot) response;
        }
    }

    private static final class PartialFeedClient implements TransitFeedClient {
        private final Queue<TransitFeedFetchResult> responses =
                new ArrayDeque<TransitFeedFetchResult>();

        void enqueue(TransitFeedFetchResult response) {
            responses.add(response);
        }

        @Override
        public TransitFeedSnapshot fetch() throws IOException {
            throw new UnsupportedOperationException("Use fetchFeeds");
        }

        @Override
        public TransitFeedFetchResult fetchFeeds() {
            return responses.remove();
        }
    }
}
