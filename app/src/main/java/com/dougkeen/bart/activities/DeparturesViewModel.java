package com.dougkeen.bart.activities;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.dougkeen.bart.backend.RouteDepartureProjection;
import com.dougkeen.bart.backend.TransitFeedSnapshot;
import com.dougkeen.bart.backend.TransitProjectionListener;
import com.dougkeen.bart.backend.TransitRepository;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.SystemTimeSource;
import com.dougkeen.bart.model.TimeSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Screen-owned realtime state retained across departures-screen recreation. */
public final class DeparturesViewModel extends ViewModel {
    public enum Status {
        LOADING,
        CONTENT,
        EMPTY,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        private final Status status;
        private final List<Departure> departures;
        private final Exception error;

        private State(Status status, List<Departure> departures, Exception error) {
            this.status = status;
            this.departures = departures;
            this.error = error;
        }

        public Status getStatus() {
            return status;
        }

        public List<Departure> getDepartures() {
            return departures;
        }

        public Exception getError() {
            return error;
        }

        private static State loading() {
            return new State(Status.LOADING, Collections.emptyList(), null);
        }

        private static State content(List<Departure> departures) {
            return new State(Status.CONTENT, departures, null);
        }

        private static State empty() {
            return new State(Status.EMPTY, Collections.emptyList(), null);
        }

        private static State error(Exception exception, List<Departure> departures) {
            return new State(Status.ERROR, departures, exception);
        }
    }

    private final TimeSource timeSource;
    private List<Departure> departures = Collections.emptyList();
    private State state = State.loading();
    private Listener listener;
    private TransitRepository.Subscription subscription;

    public DeparturesViewModel() {
        this(SystemTimeSource.INSTANCE);
    }

    public DeparturesViewModel(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    public synchronized State getState() {
        return state;
    }

    public void start(TransitRepository repository, Context context,
                      StationPair stationPair, Listener listener) {
        State currentState;
        synchronized (this) {
            stopSubscriptionLocked();
            this.listener = listener;
            currentState = state;
            if (stationPair != null) {
                subscription = repository.subscribe(
                        new RouteDepartureProjection(stationPair, context),
                        new TransitProjectionListener<RealTimeDepartures>() {
                            @Override
                            public void onData(RealTimeDepartures result,
                                               TransitFeedSnapshot snapshot) {
                                updateFromFeed(result.getDepartures());
                            }

                            @Override
                            public void onError(Exception exception,
                                                TransitFeedSnapshot snapshot) {
                                updateError(exception);
                            }
                        });
            }
        }
        if (listener != null) {
            listener.onStateChanged(currentState);
        }
    }

    public synchronized void stop() {
        stopSubscriptionLocked();
        listener = null;
    }

    public synchronized List<Departure> replace(List<Departure> incoming) {
        departures = immutableCopy(Departure.replaceFeed(departures, incoming, timeSource));
        state = departures.isEmpty() ? State.empty() : State.content(departures);
        return departures;
    }

    public synchronized List<Departure> clear() {
        departures = Collections.emptyList();
        state = State.empty();
        return departures;
    }

    public synchronized List<Departure> getDepartures() {
        return departures;
    }

    private static List<Departure> immutableCopy(List<Departure> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    protected void onCleared() {
        stop();
        synchronized (this) {
            departures = Collections.emptyList();
            state = State.loading();
        }
        super.onCleared();
    }

    private void updateFromFeed(List<Departure> incoming) {
        Listener currentListener;
        State nextState;
        synchronized (this) {
            departures = immutableCopy(Departure.replaceFeed(departures, incoming, timeSource));
            state = departures.isEmpty() ? State.empty() : State.content(departures);
            nextState = state;
            currentListener = listener;
        }
        if (currentListener != null) {
            currentListener.onStateChanged(nextState);
        }
    }

    private void updateError(Exception exception) {
        Listener currentListener;
        State nextState;
        synchronized (this) {
            state = State.error(exception, departures);
            nextState = state;
            currentListener = listener;
        }
        if (currentListener != null) {
            currentListener.onStateChanged(nextState);
        }
    }

    private void stopSubscriptionLocked() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
