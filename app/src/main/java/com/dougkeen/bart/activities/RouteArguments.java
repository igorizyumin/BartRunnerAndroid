package com.dougkeen.bart.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;

/** Primitive route and trip arguments shared by activity boundaries. */
public final class RouteArguments {
    public static final String ORIGIN = "routeOrigin";
    public static final String DESTINATION = "routeDestination";
    public static final String DEPARTURE_IDENTITY = "departureIdentity";
    public static final String SCREEN_MODE = "screenMode";

    public static final String MODE_SCHEDULE = "schedule";
    public static final String MODE_FOLLOWED = "followed";

    private RouteArguments() {
    }

    public static void putRoute(Intent intent, StationPair route) {
        if (intent == null || route == null) {
            return;
        }
        intent.putExtra(ORIGIN, abbreviation(route.getOrigin()));
        intent.putExtra(DESTINATION, abbreviation(route.getDestination()));
    }

    public static void putRoute(Bundle bundle, StationPair route) {
        if (bundle == null || route == null) {
            return;
        }
        bundle.putString(ORIGIN, abbreviation(route.getOrigin()));
        bundle.putString(DESTINATION, abbreviation(route.getDestination()));
    }

    public static void putTrip(Intent intent, StationPair route, String identity,
                               String mode) {
        putRoute(intent, route);
        intent.putExtra(DEPARTURE_IDENTITY, identity);
        intent.putExtra(SCREEN_MODE, mode);
    }

    @Nullable
    public static StationPair readRoute(Intent intent) {
        return readRoute(intent == null ? null : intent.getExtras());
    }

    @Nullable
    public static StationPair readRoute(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        Station origin = Station.getByAbbreviation(bundle.getString(ORIGIN));
        Station destination = Station.getByAbbreviation(bundle.getString(DESTINATION));
        return origin == null ? null : new StationPair(origin, destination);
    }

    @Nullable
    public static String readDepartureIdentity(Intent intent) {
        return intent == null ? null : intent.getStringExtra(DEPARTURE_IDENTITY);
    }

    @Nullable
    public static String readScreenMode(Intent intent) {
        return intent == null ? null : intent.getStringExtra(SCREEN_MODE);
    }

    private static String abbreviation(Station station) {
        return station == null ? null : station.abbreviation;
    }
}
