package `in`.izyum.bart.model

/** Describes where an individual predicted time came from. */
enum class PredictionSource {
    REALTIME,
    SCHEDULE,
    ESTIMATE,
    UNKNOWN,
}
