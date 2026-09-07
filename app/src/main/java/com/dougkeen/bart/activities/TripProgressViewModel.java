package com.dougkeen.bart.activities;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.backend.RouteDepartureProjection;
import com.dougkeen.bart.backend.TransitProjectionListener;
import com.dougkeen.bart.backend.TransitRepository;
import com.dougkeen.bart.backend.TripProgressProjection;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.SystemTimeSource;
import com.dougkeen.bart.model.TripLeg;

import java.util.List;

/** Owns live trip state and subscriptions for the trip-progress screen. */
public final class TripProgressViewModel extends AndroidViewModel {
    public interface Listener {
        void onDepartureChanged(Departure departure);
    }

    private Departure departure;
    private Listener listener;
    private TransitRepository.Subscription routeSubscription;
    private TransitRepository.Subscription tripProgressSubscription;

    public TripProgressViewModel(@NonNull Application application) {
        super(application);
    }

    public synchronized Departure getDeparture() {
        return departure;
    }

    public synchronized void start(Departure initialDeparture, Listener listener) {
        stopSubscriptionsLocked();
        departure = initialDeparture;
        this.listener = listener;

        if (departure == null || departure.getStationPair() == null) {
            return;
        }

        BartRunnerApplication application = (BartRunnerApplication) getApplication();
        TransitRepository repository = application.getTransitRepository();
        routeSubscription = repository.subscribe(
                new RouteDepartureProjection(
                        departure.getStationPair(), application),
                new TransitProjectionListener<RealTimeDepartures>() {
                    @Override
                    public void onData(RealTimeDepartures result,
                                       com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
                        updateFromRealtime(result.getDepartures());
                    }

                    @Override
                    public void onError(Exception exception,
                                        com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
                    }
                });

        if (!departure.getTripLegs().isEmpty()) {
            tripProgressSubscription = repository.subscribe(
                    new TripProgressProjection(
                            application,
                            departure.getStationPair().getOrigin(),
                            departure.getStationPair().getDestination(),
                            departure.getTripLegs()),
                    new TransitProjectionListener<List<TripLeg>>() {
                        @Override
                        public void onData(List<TripLeg> updatedLegs,
                                           com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
                            updateTripLegs(updatedLegs);
                        }

                        @Override
                        public void onError(Exception exception,
                                            com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
                        }
                    });
        }
    }

    public synchronized void stop() {
        stopSubscriptionsLocked();
        listener = null;
    }

    @Override
    protected void onCleared() {
        stop();
        super.onCleared();
    }

    private void updateFromRealtime(List<Departure> departures) {
        Departure current;
        synchronized (this) {
            current = departure;
        }
        if (current == null) {
            return;
        }
        for (Departure candidate : departures) {
            if (candidate.getIdentity().equals(current.getIdentity())) {
                Departure replacement = Departure.merge(current, candidate, true,
                        SystemTimeSource.INSTANCE);
                publish(replacement);
                return;
            }
        }
    }

    private void updateTripLegs(List<TripLeg> updatedLegs) {
        Departure current;
        synchronized (this) {
            current = departure;
        }
        if (current != null) {
            publish(current.replaceTripLegs(updatedLegs));
        }
    }

    private void publish(Departure replacement) {
        Listener currentListener;
        synchronized (this) {
            departure = replacement;
            currentListener = listener;
        }
        if (currentListener != null) {
            currentListener.onDepartureChanged(replacement);
        }
    }

    private void stopSubscriptionsLocked() {
        if (routeSubscription != null) {
            routeSubscription.close();
            routeSubscription = null;
        }
        if (tripProgressSubscription != null) {
            tripProgressSubscription.close();
            tripProgressSubscription = null;
        }
    }
}
