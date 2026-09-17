package `in`.izyum.bart.networktasks

import `in`.izyum.bart.model.RealTimeDepartures
import `in`.izyum.bart.model.Route
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.model.SystemTimeSource
import `in`.izyum.bart.backend.Schedule
import `in`.izyum.bart.transit.gtfs.BartGtfsNetwork
import `in`.izyum.bart.transit.normalization.NormalizedRealtimeFeed
import com.google.transit.realtime.GtfsRealtime
import `in`.izyum.bart.model.TripLeg

/**
 * Compatibility façade for code that still submits raw or normalized feeds.
 * Canonical production projections are owned by backend projectors.
 */
@Deprecated(
    "Legacy raw-feed compatibility façade; use RouteDepartureProjection or " +
        "TripProgressProjection with a canonical snapshot.",
    level = DeprecationLevel.WARNING,
)
@Suppress("DEPRECATION")
class GtfsRealtimeContentHandler @JvmOverloads constructor(
    origin: Station,
    destination: Station?,
    routes: List<Route>,
    ignoreDirection: Boolean,
    bartGtfsNetwork: BartGtfsNetwork,
    timeSource: TimeSource = SystemTimeSource,
) {
    private val legacy = LegacyRealtimeProjection(
        origin,
        destination,
        routes,
        ignoreDirection,
        bartGtfsNetwork,
        timeSource,
    )

    constructor(
        origin: Station,
        destination: Station?,
        ignoreDirection: Boolean,
        bartGtfsNetwork: BartGtfsNetwork,
        timeSource: TimeSource = SystemTimeSource,
    ) : this(
        origin,
        destination,
        emptyList(),
        ignoreDirection,
        bartGtfsNetwork,
        timeSource,
    )

    @Deprecated(
        "Pass a CanonicalTransitSnapshot; raw-feed processing belongs before projection.",
        level = DeprecationLevel.WARNING,
    )
    fun getRealTimeDepartures(feed: GtfsRealtime.FeedMessage): RealTimeDepartures =
        legacy.getRealTimeDepartures(feed)

    @Deprecated(
        "Pass a CanonicalTransitSnapshot; feed normalization belongs before projection.",
        level = DeprecationLevel.WARNING,
    )
    fun getRealTimeDepartures(
        normalizedFeed: NormalizedRealtimeFeed,
        feedTime: Long,
    ): RealTimeDepartures = legacy.getRealTimeDepartures(normalizedFeed, feedTime)

    @Deprecated(
        "Pass a CanonicalTransitSnapshot; canonical data is the only supported route projection input.",
        level = DeprecationLevel.WARNING,
    )
    fun getRealTimeDepartures(
        normalizedFeed: NormalizedRealtimeFeed,
        feedTime: Long,
        schedule: Schedule,
    ): RealTimeDepartures = legacy.getRealTimeDepartures(normalizedFeed, feedTime, schedule)

    @Deprecated(
        "Pass canonical trip state to the trip-progress projector; raw-feed processing is legacy.",
        level = DeprecationLevel.WARNING,
    )
    fun updateTripLegs(
        feed: GtfsRealtime.FeedMessage,
        existingLegs: List<TripLeg>,
        feedTime: Long,
    ): List<TripLeg> = legacy.updateTripLegs(feed, existingLegs, feedTime)

    @Deprecated(
        "Pass canonical trip state to the trip-progress projector; normalized-feed processing is legacy.",
        level = DeprecationLevel.WARNING,
    )
    fun updateTripLegs(
        normalizedFeed: NormalizedRealtimeFeed,
        existingLegs: List<TripLeg>,
        feedTime: Long,
    ): List<TripLeg> = legacy.updateTripLegs(normalizedFeed, existingLegs, feedTime)

    @Deprecated(
        "Migrate trip-progress refresh to canonical trip state.",
        level = DeprecationLevel.WARNING,
    )
    fun updateTripLegs(
        normalizedFeed: NormalizedRealtimeFeed,
        existingLegs: List<TripLeg>,
        feedTime: Long,
        schedule: Schedule,
    ): List<TripLeg> = legacy.updateTripLegs(normalizedFeed, existingLegs, feedTime, schedule)
}
