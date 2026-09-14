package `in`.izyum.bart.model

/** A stop arrival belonging to a train leg. */
class TripStop(
    val station: Station?,
    val arrivalTime: Long,
    val departureTime: Long,
    val scheduledArrivalTime: Long = 0L,
    val scheduledDepartureTime: Long = 0L,
    val arrivalSource: PredictionSource = PredictionSource.UNKNOWN,
    val departureSource: PredictionSource = PredictionSource.UNKNOWN,
) {
    constructor(station: Station?, arrivalTime: Long) : this(
        station,
        arrivalTime,
        arrivalTime
    )

}
