package `in`.izyum.bart.networktasks

/** External BART endpoints and credentials shared by the network clients. */
object BartApiConfig {
    const val LEGACY_API_KEY = "MW9S-E7SL-26DU-VV8V"
    const val LEGACY_ETD_URL = "https://api.bart.gov/api/etd.aspx"
    const val LEGACY_ELEVATOR_URL = "https://api.bart.gov/api/bsa.aspx"
    const val GTFS_RT_TRIP_UPDATES_URL = "https://api.bart.gov/gtfsrt/tripupdate.aspx"
    const val GTFS_RT_ALERTS_URL = "https://api.bart.gov/gtfsrt/alerts.aspx"
    const val STATIC_GTFS_URL = "https://www.bart.gov/dev/schedules/google_transit.zip"
}
