package `in`.izyum.bart.backend

import `in`.izyum.bart.model.Alert
import `in`.izyum.bart.networktasks.GtfsRealtimeFeedIndex
import `in`.izyum.bart.performance.PerformanceTrace
import com.google.transit.realtime.GtfsRealtime

/** Converts the latest alert feed into the app's alert model. */
class AlertProjection {
    fun project(snapshot: TransitFeedSnapshot): Alert.AlertList {
        return PerformanceTrace.section("BART alert projection") {
            val index: GtfsRealtimeFeedIndex = snapshot.getAlertIndex()
            val alerts = mutableListOf<Alert>()
            for (entity in index.alertEntities) {
                if (!entity.hasAlert()) {
                    continue
                }
                val source = entity.alert
                alerts += Alert(
                    id = entity.id,
                    type = if (source.hasEffect()) source.effect.name else "",
                    description = if (source.hasDescriptionText()) {
                        translation(source.descriptionText)
                    } else {
                        ""
                    }
                )
            }
            Alert.AlertList(alerts, alerts.isEmpty())
        }
    }

    fun areEquivalent(
        previous: Alert.AlertList?,
        current: Alert.AlertList?
    ): Boolean {
        if (previous === current) {
            return true
        }
        if (previous == null || current == null
            || previous.areNoDelaysReported() != current.areNoDelaysReported()
            || previous.getAlerts().size != current.getAlerts().size
        ) {
            return false
        }
        for (index in previous.getAlerts().indices) {
            val left = previous.getAlerts()[index]
            val right = current.getAlerts()[index]
            if (left.id != right.id
                || left.type != right.type
                || left.description != right.description
            ) {
                return false
            }
        }
        return true
    }

    private fun translation(value: GtfsRealtime.TranslatedString?): String =
        if (value == null || value.translationList.isEmpty()) ""
        else value.getTranslation(0).text
}
