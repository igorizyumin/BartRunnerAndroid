package com.dougkeen.bart.activities;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.dougkeen.bart.backend.AlertProjection;
import com.dougkeen.bart.backend.RouteDepartureProjection;
import com.dougkeen.bart.backend.TransitProjectionState;
import com.dougkeen.bart.backend.TransitRepository;
import com.dougkeen.bart.data.ViewModelFlowCollector;
import com.dougkeen.bart.model.Alert;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TimeSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import kotlinx.coroutines.Job;

/** Owns live route summaries and alerts for the favorite-routes screen. */
public final class RoutesTransitViewModel extends ViewModel {
    public interface Listener {
        void onFirstDeparturesChanged(Map<StationPair, Departure> firstDepartures);

        void onAlertsChanged(Alert.AlertList alerts);

        void onTransitError(Exception exception);
    }

    private final Map<StationPair, Job> subscriptions =
            new HashMap<>();
    private final Map<StationPair, Departure> firstDepartures = new HashMap<>();
    private List<StationPair> routes = Collections.emptyList();
    private Alert.AlertList latestAlerts;
    private Job alertCollectionJob;
    private TransitRepository repository;
    private Context context;
    private TimeSource timeSource;
    private Listener listener;
    private boolean started;

    public synchronized void configure(TransitRepository repository, Context context,
                                        TimeSource timeSource,
                                        Listener listener) {
        stopSubscriptionsLocked();
        this.repository = repository;
        this.context = context;
        this.timeSource = timeSource;
        this.listener = listener;
        started = true;
        syncRouteSubscriptionsLocked();
        alertCollectionJob = ViewModelFlowCollector.collect(this,
                repository.projectedState(new AlertProjection()),
                (TransitProjectionState<Alert.AlertList> projectionState) -> {
                    if (projectionState.getError() != null) {
                        publishError(projectionState.getError());
                    } else if (projectionState.getValue() != null) {
                        publishAlerts(projectionState.getValue());
                    }
                });

        Listener currentListener = this.listener;
        Map<StationPair, Departure> currentDepartures = copyDepartures();
        Alert.AlertList currentAlerts = latestAlerts;
        if (currentListener != null) {
            currentListener.onFirstDeparturesChanged(currentDepartures);
            if (currentAlerts != null) {
                currentListener.onAlertsChanged(currentAlerts);
            }
        }
    }

    public synchronized void setRoutes(List<StationPair> routes) {
        this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
        syncRouteSubscriptionsLocked();
        Listener currentListener = listener;
        Map<StationPair, Departure> currentDepartures = copyDepartures();
        if (currentListener != null) {
            currentListener.onFirstDeparturesChanged(currentDepartures);
        }
    }

    @Override
    protected void onCleared() {
        stopSubscriptionsLocked();
        started = false;
        listener = null;
        super.onCleared();
    }

    private void syncRouteSubscriptionsLocked() {
        Set<StationPair> desiredRoutes = new HashSet<>(routes);
        for (StationPair route : new ArrayList<>(subscriptions.keySet())) {
            if (!desiredRoutes.contains(route)) {
                Job collectionJob = subscriptions.remove(route);
                if (collectionJob != null) {
                    collectionJob.cancel(null);
                }
                firstDepartures.remove(route);
            }
        }

        if (!started || repository == null || context == null) {
            return;
        }

        for (StationPair route : routes) {
            if (!subscriptions.containsKey(route)) {
                subscriptions.put(route, ViewModelFlowCollector.collect(this,
                        repository.projectedState(
                                new RouteDepartureProjection(route, context)),
                        (TransitProjectionState<RealTimeDepartures> projectionState) -> {
                            if (projectionState.getError() != null) {
                                publishError(projectionState.getError());
                            } else if (projectionState.getValue() != null) {
                                publishRouteDepartures(
                                        route, projectionState.getValue());
                            }
                        }));
            }
        }
    }

    private void publishRouteDepartures(StationPair route,
                                        RealTimeDepartures result) {
        Departure firstDeparture = null;
        for (Departure departure : result.getDepartures()) {
            if (!departure.hasDeparted(timeSource)) {
                firstDeparture = departure;
                break;
            }
        }

        Listener currentListener;
        Map<StationPair, Departure> currentDepartures;
        synchronized (this) {
            if (!subscriptions.containsKey(route)) {
                return;
            }
            Departure previous = firstDepartures.get(route);
            if (Objects.equals(previous, firstDeparture)) {
                return;
            }
            if (firstDeparture == null) {
                firstDepartures.remove(route);
            } else {
                firstDepartures.put(route, firstDeparture);
            }
            currentListener = listener;
            currentDepartures = copyDepartures();
        }
        if (currentListener != null) {
            currentListener.onFirstDeparturesChanged(currentDepartures);
        }
    }

    private void publishAlerts(Alert.AlertList alerts) {
        Listener currentListener;
        synchronized (this) {
            latestAlerts = alerts;
            currentListener = listener;
        }
        if (currentListener != null) {
            currentListener.onAlertsChanged(alerts);
        }
    }

    private synchronized void publishError(Exception exception) {
        if (listener != null) {
            listener.onTransitError(exception);
        }
    }

    private synchronized Map<StationPair, Departure> copyDepartures() {
        return Collections.unmodifiableMap(new HashMap<>(firstDepartures));
    }

    private void stopSubscriptionsLocked() {
        for (Job collectionJob : subscriptions.values()) {
            collectionJob.cancel(null);
        }
        subscriptions.clear();
        if (alertCollectionJob != null) {
            alertCollectionJob.cancel(null);
            alertCollectionJob = null;
        }
    }

}
