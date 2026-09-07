package com.dougkeen.bart.networktasks;

import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.Line;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.Route;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.TripStop;
import com.google.transit.realtime.GtfsRealtime;
import com.dougkeen.bart.transit.gtfs.BartGtfsNetwork;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Converts BART's GTFS-RT trip updates into the app's departure model. */
public class GtfsRealtimeContentHandler {
    private static final long ESTIMATE_TOLERANCE_MILLIS = 30000L;
    private static final TimeZone PACIFIC_TIME =
            TimeZone.getTimeZone("America/Los_Angeles");

    private final Station origin;
    private final Station destination;
    private final List<Route> routes;
    private final boolean ignoreDirection;
    private final BartGtfsNetwork bartGtfsNetwork;

    public GtfsRealtimeContentHandler(Station origin, Station destination,
                                      List<Route> routes,
                                      boolean ignoreDirection,
                                      BartGtfsNetwork bartGtfsNetwork) {
        if (bartGtfsNetwork == null) {
            throw new IllegalArgumentException("A validated GTFS network is required");
        }
        this.origin = origin;
        this.destination = destination;
        this.routes = routes;
        this.ignoreDirection = ignoreDirection;
        this.bartGtfsNetwork = bartGtfsNetwork;
    }

    public RealTimeDepartures getRealTimeDepartures(
            GtfsRealtime.FeedMessage feed) {
        return getRealTimeDepartures(GtfsRealtimeFeedIndex.from(feed),
                feedTime(feed));
    }

    public RealTimeDepartures getRealTimeDepartures(
            GtfsRealtimeFeedIndex feedIndex, long feedTime) {
        RealTimeDepartures departures = new RealTimeDepartures(origin,
                destination, routes, bartGtfsNetwork);
        departures.setTime(feedTime);

        List<TripSnapshot> trips = parseTrips(feedIndex.getTripUpdateEntities(),
                feedTime);
        for (TripSnapshot trip : trips) {
            addTripUpdate(departures, trip, trips);
        }
        return departures;
    }

    /**
     * Replaces the stop estimates for an already-selected itinerary using
     * the latest update for each exact train. A train may no longer include
     * the passenger's origin in the feed after it has departed, so missing
     * passed stops are deliberately retained from the previous snapshot.
     */
    public List<TripLeg> updateTripLegs(GtfsRealtime.FeedMessage feed,
                                        List<TripLeg> existingLegs,
                                        long feedTime) {
        return updateTripLegs(GtfsRealtimeFeedIndex.from(feed), existingLegs,
                feedTime);
    }

    public List<TripLeg> updateTripLegs(GtfsRealtimeFeedIndex feedIndex,
                                        List<TripLeg> existingLegs,
                                        long feedTime) {
        List<TripSnapshot> trips = parseTrips(feedIndex.getTripUpdateEntities(),
                feedTime);
        Map<String, TripSnapshot> tripsById = new HashMap<String, TripSnapshot>();
        for (TripSnapshot trip : trips) {
            if (trip.tripId != null && !trip.tripId.isEmpty()) {
                tripsById.put(trip.tripId, trip);
            }
        }

        List<TripLeg> updatedLegs = new ArrayList<TripLeg>();
        for (TripLeg existing : existingLegs) {
            TripSnapshot current = tripsById.get(existing.getTripId());
            if (current == null) {
                current = findMatchingTrip(existing, trips);
            }
            updatedLegs.add(current == null
                    ? existing : updateTripLeg(existing, current));
        }
        return updatedLegs;
    }

    private List<TripSnapshot> parseTrips(
            List<GtfsRealtime.FeedEntity> entities, long feedTime) {
        List<TripSnapshot> trips = new ArrayList<TripSnapshot>();
        for (GtfsRealtime.FeedEntity entity : entities) {
            if (entity.hasTripUpdate()) {
                TripSnapshot trip = parseTrip(entity.getTripUpdate(),
                        feedTime);
                if (trip != null) {
                    trips.add(trip);
                }
            }
        }
        return trips;
    }

    private static long feedTime(GtfsRealtime.FeedMessage feed) {
        return feed.hasHeader() && feed.getHeader().hasTimestamp()
                && feed.getHeader().getTimestamp() > 0
                ? feed.getHeader().getTimestamp() * 1000L
                : System.currentTimeMillis();
    }

    private TripSnapshot findMatchingTrip(TripLeg leg,
                                          List<TripSnapshot> trips) {
        for (TripSnapshot trip : trips) {
            if (trip.line == leg.getLine()
                    && trip.trainDestination == leg.getTrainDestination()
                    && trip.canServe(leg.getOrigin(), leg.getDestination())) {
                return trip;
            }
        }
        return null;
    }

    private TripLeg updateTripLeg(TripLeg existing, TripSnapshot trip) {
        TripLeg updated = new TripLeg();
        updated.setLine(lineForDestination(trip.line, trip.trainDestination));
        updated.setOrigin(existing.getOrigin());
        updated.setDestination(existing.getDestination());
        updated.setTrainDestination(trip.trainDestination);
        updated.setTripId(existing.getTripId());

        StopTimePoint origin = trip.pointAt(existing.getOrigin());
        StopTimePoint destination = trip.pointAt(existing.getDestination());
        updated.setDepartureTime(origin == null ? existing.getDepartureTime()
                : origin.departureTime);
        updated.setArrivalTime(destination == null
                ? existing.getArrivalTime() : destination.arrivalTime);

        List<TripStop> stops = new ArrayList<TripStop>();
        for (TripStop existingStop : existing.getStops()) {
            StopTimePoint stop = trip.pointAt(existingStop.getStation());
            stops.add(stop == null
                    ? existingStop
                    : new TripStop(existingStop.getStation(), stop.arrivalTime,
                            stop.departureTime));
        }
        updated.setStops(stops);
        return updated;
    }

    private void addTripUpdate(RealTimeDepartures departures,
                               TripSnapshot trip,
                               List<TripSnapshot> allTrips) {
        if (destination != null && !ignoreDirection && !origin.ignoreRoutingDirection
                && !isDirectionApplicable(trip.direction)) {
            return;
        }
        StopTimePoint originPoint = trip.pointAt(origin);
        if (originPoint == null || originPoint.departureTime <= 0) {
            return;
        }
        Route route = findRoute(trip);
        if (route == null) {
            return;
        }

        Departure departure = new Departure();
        departure.setOrigin(origin);
        departure.setTrainDestination(trip.trainDestination);
        departure.setLine(lineForDestination(trip.line, trip.trainDestination));
        departure.setDirection(trip.direction);
        departure.setPlatform(trip.platform);
        departure.setLimited(false);
        departure.setCanceled(trip.canceled);
        departure.setTrainDestinationColorText(departure.getLine().name());
        departure.setTrainDestinationColorHex(colorForLine(departure.getLine()));

        int minutes = (int) Math.max(0L, (originPoint.departureTime
                - departures.getTime()) / 60000L);
        departure.setMinutes(minutes);
        departures.addDeparture(departure);
        departure.setMinEstimate(originPoint.departureTime
                - ESTIMATE_TOLERANCE_MILLIS);
        departure.setMaxEstimate(originPoint.departureTime
                + ESTIMATE_TOLERANCE_MILLIS);

        List<TripLeg> legs = buildTripLegs(route, trip, allTrips);
        if (!legs.isEmpty()) {
            departure.setTripLegs(legs);
            if (legs.get(legs.size() - 1).hasArrivalTime()) {
                departure.setEstimatedTripTime((int) (legs.get(legs.size() - 1)
                        .getArrivalTime() - originPoint.departureTime));
            }
        }
    }

    private TripSnapshot parseTrip(GtfsRealtime.TripUpdate tripUpdate,
                                   long feedTime) {
        if (!tripUpdate.hasTrip()) {
            return null;
        }
        GtfsRealtime.TripDescriptor trip = tripUpdate.getTrip();
        String routeId = trip.hasRouteId() && !trip.getRouteId().isEmpty()
                ? trip.getRouteId() : null;
        if (routeId == null || routeId.isEmpty()) {
            routeId = bartGtfsNetwork.routeIdForTrip(trip.getTripId());
        }
        Line line = bartGtfsNetwork.lineForRouteId(routeId);
        if (line == null) {
            return null;
        }

        TripSnapshot result = new TripSnapshot();
        result.tripId = trip.getTripId();
        result.line = line;
        result.direction = directionForLine(line, routeId);
        result.points = new ArrayList<StopTimePoint>();
        int updateIndex = 0;
        for (GtfsRealtime.TripUpdate.StopTimeUpdate update
                : tripUpdate.getStopTimeUpdateList()) {
            if (isSkipped(update)) {
                updateIndex++;
                continue;
            }
            Station station = bartGtfsNetwork.stationForStopId(update.getStopId());
            long departure = departureTime(update);
            long arrival = arrivalTime(update);
            if (station != null && station != Station.SPCL
                    && (departure > 0 || arrival > 0)) {
                StopTimePoint point = new StopTimePoint();
                point.station = station;
                point.order = stopOrder(update, updateIndex);
                point.departureTime = departure > 0 ? departure : arrival;
                point.arrivalTime = arrival > 0 ? arrival : departure;
                result.points.add(point);
                if (result.trainDestination == null
                        || point.order > result.lastOrder) {
                    result.trainDestination = station;
                    result.lastOrder = point.order;
                }
                if (station == origin && result.platform == null) {
                    result.platform = platformForStopId(update.getStopId());
                }
            }
            updateIndex++;
        }
        if (result.trainDestination == null) {
            return null;
        }
        result.canceled = trip.hasScheduleRelationship()
                && trip.getScheduleRelationship()
                == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED;
        if (result.pointAt(origin) == null && result.canceled) {
            // A canceled trip can still be displayed if BART supplies its
            // start time, matching the old ETD behavior.
            long start = scheduledStartTime(trip, feedTime);
            if (start > 0) {
                StopTimePoint point = new StopTimePoint();
                point.station = origin;
                point.departureTime = start;
                point.arrivalTime = start;
                point.order = Integer.MIN_VALUE;
                result.points.add(point);
            }
        }
        return result;
    }

    private Route findRoute(TripSnapshot trip) {
        for (Route route : routes) {
            if (route.trainDestinationIsApplicable(trip.trainDestination,
                    trip.line)) {
                return route;
            }
        }
        return null;
    }

    private List<TripLeg> buildTripLegs(Route route, TripSnapshot firstTrip,
                                        List<TripSnapshot> allTrips) {
        // A station-only lookup has no requested passenger destination, but
        // the selected train still gives us the endpoint to display. Treat it
        // as a single-leg trip, just like a lookup made directly to that
        // endpoint.
        Station tripDestination = destination == null
                ? firstTrip.trainDestination : destination;
        if (tripDestination == null) {
            return Collections.emptyList();
        }
        List<Line> lines = route.getLines();
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }
        List<Station> transfers = route.getTransferStations();
        List<TripLeg> result = new ArrayList<TripLeg>();
        TripSnapshot currentTrip = firstTrip;
        Station legOrigin = origin;
        for (int i = 0; i < lines.size(); i++) {
            Station legDestination = i < transfers.size()
                    ? transfers.get(i) : tripDestination;
            if (i > 0) {
                long earliestDeparture = result.get(i - 1).getArrivalTime();
                currentTrip = findConnectingTrip(lines.get(i), legOrigin,
                        legDestination, earliestDeparture, allTrips);
                if (currentTrip == null) {
                    break;
                }
            }
            StopTimePoint departure = currentTrip.pointAt(legOrigin);
            StopTimePoint arrival = currentTrip.pointAt(legDestination);
            if (departure == null) {
                break;
            }
            TripLeg leg = new TripLeg();
            leg.setLine(lineForDestination(currentTrip.line,
                    currentTrip.trainDestination));
            leg.setOrigin(legOrigin);
            leg.setDestination(legDestination);
            leg.setTrainDestination(currentTrip.trainDestination);
            leg.setTripId(currentTrip.tripId);
            leg.setDepartureTime(departure.departureTime);
            leg.setArrivalTime(arrival == null ? 0L : arrival.arrivalTime);
            List<TripStop> stops = new ArrayList<TripStop>();
            for (StopTimePoint point : currentTrip.pointsBetween(legOrigin,
                    legDestination)) {
                stops.add(new TripStop(point.station, point.arrivalTime,
                        point.departureTime));
            }
            leg.setStops(stops);
            result.add(leg);
            legOrigin = legDestination;
        }
        return result;
    }

    private TripSnapshot findConnectingTrip(Line line, Station origin,
                                            Station destination,
                                            long earliestDeparture,
                                            List<TripSnapshot> allTrips) {
        TripSnapshot best = null;
        for (TripSnapshot trip : allTrips) {
            if (trip.line != line || !trip.canServe(origin, destination)) {
                continue;
            }
            StopTimePoint departure = trip.pointAt(origin);
            if (departure == null || departure.departureTime < earliestDeparture) {
                continue;
            }
            if (best == null || departure.departureTime
                    < best.pointAt(origin).departureTime) {
                best = trip;
            }
        }
        return best;
    }

    private static class StopTimePoint {
        private Station station;
        private int order;
        private long departureTime;
        private long arrivalTime;
    }

    private static class TripSnapshot {
        private String tripId;
        private Line line;
        private String direction;
        private Station trainDestination;
        private String platform;
        private boolean canceled;
        private int lastOrder = Integer.MIN_VALUE;
        private List<StopTimePoint> points;

        private StopTimePoint pointAt(Station station) {
            for (StopTimePoint point : points) {
                if (point.station == station) {
                    return point;
                }
            }
            return null;
        }

        private boolean canServe(Station origin, Station destination) {
            StopTimePoint start = pointAt(origin);
            StopTimePoint end = pointAt(destination);
            return start != null && end != null && end.order > start.order;
        }

        private List<StopTimePoint> pointsBetween(Station origin,
                                                  Station destination) {
            StopTimePoint start = pointAt(origin);
            StopTimePoint end = pointAt(destination);
            if (start == null || end == null) {
                return Collections.emptyList();
            }
            List<StopTimePoint> result = new ArrayList<StopTimePoint>();
            for (StopTimePoint point : points) {
                if (point.order >= start.order && point.order <= end.order) {
                    result.add(point);
                }
            }
            return result;
        }
    }

    private boolean isDirectionApplicable(String direction) {
        for (Route route : routes) {
            if (direction.equals(route.getDirection())) {
                return true;
            }
        }
        return false;
    }

    private String directionForLine(Line line, String routeId) {
        for (Route route : routes) {
            if (line.equals(route.getDirectLine())
                    || (route.getTransferLines() != null
                    && route.getTransferLines().contains(line))) {
                return route.getDirection();
            }
        }
        return bartGtfsNetwork.directionForRouteId(routeId);
    }

    private static long departureTime(
            GtfsRealtime.TripUpdate.StopTimeUpdate update) {
        if (update.hasDeparture() && update.getDeparture().hasTime()) {
            return update.getDeparture().getTime() * 1000L;
        }
        if (update.hasArrival() && update.getArrival().hasTime()) {
            return update.getArrival().getTime() * 1000L;
        }
        return 0L;
    }

    private static long arrivalTime(
            GtfsRealtime.TripUpdate.StopTimeUpdate update) {
        if (update.hasArrival() && update.getArrival().hasTime()) {
            return update.getArrival().getTime() * 1000L;
        }
        if (update.hasDeparture() && update.getDeparture().hasTime()) {
            return update.getDeparture().getTime() * 1000L;
        }
        return 0L;
    }

    private static long scheduledStartTime(GtfsRealtime.TripDescriptor trip,
                                           long feedTime) {
        if (!trip.hasStartTime()) {
            return 0L;
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dateFormat.setTimeZone(PACIFIC_TIME);
        String startDate = trip.hasStartDate() ? trip.getStartDate()
                : dateFormat.format(new Date(feedTime));
        String[] timeParts = trip.getStartTime().split(":");
        if (timeParts.length != 3) {
            return 0L;
        }
        try {
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            int second = Integer.parseInt(timeParts[2]);
            Calendar date = Calendar.getInstance(PACIFIC_TIME, Locale.US);
            date.clear();
            date.setTime(dateFormat.parse(startDate));
            date.add(Calendar.HOUR_OF_DAY, hour);
            date.add(Calendar.MINUTE, minute);
            date.add(Calendar.SECOND, second);
            return date.getTimeInMillis();
        } catch (NumberFormatException | ParseException e) {
            return 0L;
        }
    }

    private static boolean isSkipped(
            GtfsRealtime.TripUpdate.StopTimeUpdate update) {
        return update.hasScheduleRelationship()
                && update.getScheduleRelationship()
                == GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED;
    }

    private static int stopOrder(
            GtfsRealtime.TripUpdate.StopTimeUpdate update, int listIndex) {
        // BART currently omits stop_sequence from its GTFS-RT feed. The feed
        // order is still the trip order, so use it when no sequence exists.
        return update.hasStopSequence() ? update.getStopSequence() : listIndex;
    }

    private static String platformForStopId(String stopId) {
        if (stopId == null) {
            return null;
        }
        int separator = stopId.lastIndexOf('-');
        return separator >= 0 && separator + 1 < stopId.length()
                ? stopId.substring(separator + 1)
                : null;
    }

    private Line lineForDestination(Line line, Station trainDestination) {
        if (line == Line.YELLOW
                && (trainDestination == Station.MLBR || origin == Station.MLBR)) {
            return Line.YELLOW_LATE_NIGHT;
        }
        return line;
    }

    private static String colorForLine(Line line) {
        switch (line) {
            case RED:
                return "#ffff0000";
            case ORANGE:
                return "#ffff9933";
            case YELLOW:
            case YELLOW_LATE_NIGHT:
                return "#ffffff33";
            case GREEN:
                return "#ff339933";
            case BLUE:
                return "#ff0099cc";
            default:
                return "#ffffffff";
        }
    }
}
