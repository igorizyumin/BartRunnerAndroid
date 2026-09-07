package com.dougkeen.bart.model

/** A service alert projected from GTFS realtime. */
class Alert(private val id: String) {
    private var type: String? = null
    private var description: String? = null
    private var postedTime: String? = null
    private var expiresTime: String? = null

    fun getId(): String = id

    fun getType(): String? = type

    fun setType(type: String?) {
        this.type = type
    }

    fun getDescription(): String? = description

    fun setDescription(description: String?) {
        this.description = description
    }

    fun getPostedTime(): String? = postedTime

    fun setPostedTime(postedTime: String?) {
        this.postedTime = postedTime
    }

    fun getExpiresTime(): String? = expiresTime

    fun setExpiresTime(expiresTime: String?) {
        this.expiresTime = expiresTime
    }

    class AlertList {
        private val alerts = mutableListOf<Alert>()
        private var noDelaysReported = false

        fun getAlerts(): MutableList<Alert> = alerts

        fun addAlert(alert: Alert) {
            alerts += alert
        }

        fun clear() {
            alerts.clear()
        }

        fun hasAlerts(): Boolean = alerts.isNotEmpty()

        fun areNoDelaysReported(): Boolean = noDelaysReported

        fun setNoDelaysReported(noDelaysReported: Boolean) {
            this.noDelaysReported = noDelaysReported
        }
    }
}
