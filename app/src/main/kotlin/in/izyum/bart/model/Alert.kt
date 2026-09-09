package `in`.izyum.bart.model

/** An immutable service alert projected from GTFS realtime. */
data class Alert(
    val id: String,
    val type: String? = null,
    val description: String? = null,
    val postedAtMillis: Long? = null,
    val expiresAtMillis: Long? = null
) {
    /** Immutable alert projection result. */
    class AlertList(
        alerts: List<Alert>,
        private val noDelaysReported: Boolean
    ) {
        private val alerts: List<Alert> = immutableList(alerts)

        fun getAlerts(): List<Alert> = alerts

        fun hasAlerts(): Boolean = alerts.isNotEmpty()

        fun areNoDelaysReported(): Boolean = noDelaysReported
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    java.util.Collections.unmodifiableList(java.util.ArrayList(values))
