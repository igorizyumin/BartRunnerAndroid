# Canonical fixture matrix

The canonical regression suite now keeps the feed-shape cases below visible
as a review matrix. Small synthetic GTFS inputs live beside the focused unit
tests; timestamped protobuf captures cover the live-shape regressions.

| Case | Coverage |
| --- | --- |
| after-midnight service | `StaticTransitNormalizerTest` and `ScheduleTest` |
| calendar exceptions and service-date identity | `GtfsNetworkCatalogTest`, `ScheduleTest` |
| parent stations and platform stops | `StaticTransitNormalizerTest`, `CanonicalProjectionRegressionTest` |
| missing route IDs or stop sequences | `GtfsNetworkCatalogTest`, `RealtimeFeedSnapshotTest` |
| partial stop lists | `LiveGtfsRoutingTest.partialRealtime...` cases |
| explicit cancellations and duplicate entities | `CanonicalProjectionRegressionTest`, `TransitFeedSnapshotTest` |
| stale origins and completed legs | `LiveGtfsRoutingTest`, `ItineraryRefreshProjectorTest` |
| DMU observations and required electric transfer | `CanonicalTransitSnapshotTest`, `LiveGtfsRoutingTest` |
| source ambiguity and feed timestamp skew | normalization association tests and the live audit fixtures |

The live capture corpus under `app/src/test/resources/bart_live_*` remains
re-fetchable test input; production projections consume only the canonical
snapshot produced from those feed shapes.
