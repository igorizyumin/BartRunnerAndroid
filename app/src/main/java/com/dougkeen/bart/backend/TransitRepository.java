package com.dougkeen.bart.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the latest complete transit snapshot and publishes it to active
 * consumers. Network refreshes are independent of the route queries made by
 * those consumers.
 */
public final class TransitRepository implements AutoCloseable {
    public interface Listener {
        void onSnapshot(TransitFeedSnapshot snapshot);

        void onError(Exception exception, TransitFeedSnapshot lastSnapshot);
    }

    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final Object lock = new Object();
    private final TransitFeedClient feedClient;
    private final ScheduledExecutorService scheduler;
    private final Executor projectionExecutor;
    private final Executor callbackExecutor;
    private final long refreshIntervalMillis;
    private final Set<Listener> listeners = new HashSet<Listener>();

    private TransitFeedSnapshot latestSnapshot;
    private ScheduledFuture<?> scheduledRefresh;
    private boolean refreshInProgress;
    private boolean closed;

    public TransitRepository(TransitFeedClient feedClient,
                             ScheduledExecutorService scheduler,
                             Executor callbackExecutor,
                             long refreshIntervalMillis) {
        this(feedClient, scheduler, callbackExecutor, callbackExecutor,
                refreshIntervalMillis);
    }

    public TransitRepository(TransitFeedClient feedClient,
                             ScheduledExecutorService scheduler,
                             Executor projectionExecutor,
                             Executor callbackExecutor,
                             long refreshIntervalMillis) {
        if (refreshIntervalMillis <= 0) {
            throw new IllegalArgumentException("refreshIntervalMillis must be positive");
        }
        this.feedClient = feedClient;
        this.scheduler = scheduler;
        this.projectionExecutor = projectionExecutor;
        this.callbackExecutor = callbackExecutor;
        this.refreshIntervalMillis = refreshIntervalMillis;
    }

    public Subscription subscribe(final Listener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }

        TransitFeedSnapshot snapshotToReplay;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Repository is closed");
            }
            listeners.add(listener);
            snapshotToReplay = latestSnapshot;
            ensureRefreshScheduledLocked();
        }

        if (snapshotToReplay != null) {
            dispatchSnapshot(listener, snapshotToReplay);
        }

        return new Subscription() {
            private boolean active = true;

            @Override
            public void close() {
                synchronized (lock) {
                    if (!active) {
                        return;
                    }
                    active = false;
                    listeners.remove(listener);
                    if (listeners.isEmpty() && scheduledRefresh != null) {
                        scheduledRefresh.cancel(false);
                        scheduledRefresh = null;
                    }
                }
            }
        };
    }

    /** Synchronously fetches once. Intended for tests and explicit refresh actions. */
    public void refreshNow() {
        synchronized (lock) {
            if (closed || refreshInProgress) {
                return;
            }
            refreshInProgress = true;
        }

        TransitFeedSnapshot refreshedSnapshot = null;
        Exception refreshError = null;
        try {
            refreshedSnapshot = feedClient.fetch();
        } catch (Exception exception) {
            refreshError = exception;
        }

        List<Listener> listenersToNotify = new ArrayList<Listener>();
        TransitFeedSnapshot snapshotToNotify = null;
        TransitFeedSnapshot snapshotOnError;
        synchronized (lock) {
            refreshInProgress = false;
            if (!closed && refreshedSnapshot != null
                    && (latestSnapshot == null
                    || !refreshedSnapshot.hasSameFeedData(latestSnapshot))) {
                latestSnapshot = refreshedSnapshot;
                snapshotToNotify = refreshedSnapshot;
                listenersToNotify.addAll(listeners);
            } else if (!closed && refreshError != null) {
                listenersToNotify.addAll(listeners);
            }
            snapshotOnError = latestSnapshot;
            if (!closed && !listeners.isEmpty()) {
                scheduleNextRefreshLocked();
            }
        }

        if (snapshotToNotify != null) {
            for (Listener listener : listenersToNotify) {
                dispatchSnapshot(listener, snapshotToNotify);
            }
        } else if (refreshError != null) {
            for (Listener listener : listenersToNotify) {
                dispatchError(listener, refreshError, snapshotOnError);
            }
        }
    }

    public TransitFeedSnapshot getLatestSnapshot() {
        synchronized (lock) {
            return latestSnapshot;
        }
    }

    /**
     * Subscribes to a query-specific value derived from each changed snapshot.
     * Projection work is kept off the callback executor so UI delivery remains
     * lightweight.
     */
    public <T> Subscription subscribe(
            final TransitProjection<T> projection,
            final TransitProjectionListener<T> listener) {
        if (projection == null) {
            throw new NullPointerException("projection");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }

        final AtomicBoolean active = new AtomicBoolean(true);
        Subscription rawSubscription = subscribe(new Listener() {
            private final Object projectionLock = new Object();
            private T previousValue;
            private boolean hasPreviousValue;

            @Override
            public void onSnapshot(final TransitFeedSnapshot snapshot) {
                if (!active.get()) {
                    return;
                }
                projectionExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!active.get()) {
                                return;
                            }
                            final T value;
                            synchronized (projectionLock) {
                                value = projection.project(snapshot);
                                if (hasPreviousValue
                                        && projection.areEquivalent(previousValue,
                                        value)) {
                                    return;
                                }
                                previousValue = value;
                                hasPreviousValue = true;
                            }
                            callbackExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    if (active.get()) {
                                        listener.onData(value, snapshot);
                                    }
                                }
                            });
                        } catch (final Exception exception) {
                            dispatchProjectionError(exception, snapshot);
                        }
                    }

                    private void dispatchProjectionError(
                            final Exception exception,
                            final TransitFeedSnapshot snapshot) {
                        callbackExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                if (active.get()) {
                                    listener.onError(exception, snapshot);
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void onError(final Exception exception,
                                final TransitFeedSnapshot lastSnapshot) {
                callbackExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (active.get()) {
                            listener.onError(exception, lastSnapshot);
                        }
                    }
                });
            }
        });
        return new Subscription() {
            @Override
            public void close() {
                if (active.compareAndSet(true, false)) {
                    rawSubscription.close();
                }
            }
        };
    }

    private void ensureRefreshScheduledLocked() {
        if (scheduledRefresh == null && !refreshInProgress) {
            scheduledRefresh = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        scheduledRefresh = null;
                        if (closed || listeners.isEmpty()) {
                            return;
                        }
                    }
                    refreshNow();
                }
            }, 0, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleNextRefreshLocked() {
        if (scheduledRefresh == null) {
            scheduledRefresh = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        scheduledRefresh = null;
                        if (closed || listeners.isEmpty()) {
                            return;
                        }
                    }
                    refreshNow();
                }
            }, refreshIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void dispatchSnapshot(final Listener listener,
                                  final TransitFeedSnapshot snapshot) {
        callbackExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (closed || !listeners.contains(listener)) {
                        return;
                    }
                }
                listener.onSnapshot(snapshot);
            }
        });
    }

    private void dispatchError(final Listener listener,
                               final Exception exception,
                               final TransitFeedSnapshot lastSnapshot) {
        callbackExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (closed || !listeners.contains(listener)) {
                        return;
                    }
                }
                listener.onError(exception, lastSnapshot);
            }
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            listeners.clear();
            if (scheduledRefresh != null) {
                scheduledRefresh.cancel(false);
                scheduledRefresh = null;
            }
        }
    }
}
