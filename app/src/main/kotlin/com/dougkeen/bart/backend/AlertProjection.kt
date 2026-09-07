package com.dougkeen.bart.backend

import com.dougkeen.bart.model.Alert
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.google.transit.realtime.GtfsRealtime
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Converts the latest alert feed into the app's alert model. */
class AlertProjection : TransitProjection<Alert.AlertList> {
    override fun project(snapshot: TransitFeedSnapshot): Alert.AlertList {
        val result = Alert.AlertList()
        val format = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        )
        val index: GtfsRealtimeFeedIndex = snapshot.getAlertIndex()
        for (entity in index.alertEntities) {
            if (!entity.hasAlert()) {
                continue
            }
            val source = entity.alert
            val alert = Alert(entity.id)
            alert.setType(if (source.hasEffect()) source.effect.name else "")
            alert.setDescription(
                text(
                    if (source.hasHeaderText()) source.headerText else null,
                    if (source.hasDescriptionText()) source.descriptionText else null
                )
            )
            if (source.activePeriodList.isNotEmpty()) {
                val period = source.getActivePeriod(0)
                if (period.hasStart()) {
                    alert.setPostedTime(format.format(Date(period.start * 1000L)))
                }
                if (period.hasEnd()) {
                    alert.setExpiresTime(format.format(Date(period.end * 1000L)))
                }
            }
            result.addAlert(alert)
        }
        if (!result.hasAlerts()) {
            result.setNoDelaysReported(true)
        }
        return result
    }

    override fun areEquivalent(
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
            if (left.getId() != right.getId()
                || left.getType() != right.getType()
                || left.getDescription() != right.getDescription()
                || left.getPostedTime() != right.getPostedTime()
                || left.getExpiresTime() != right.getExpiresTime()
            ) {
                return false
            }
        }
        return true
    }

    private fun text(
        header: GtfsRealtime.TranslatedString?,
        description: GtfsRealtime.TranslatedString?
    ): String {
        val headerText = translation(header)
        val descriptionText = translation(description)
        return when {
            headerText.isEmpty() -> descriptionText
            descriptionText.isEmpty() -> headerText
            else -> "$headerText\n$descriptionText"
        }
    }

    private fun translation(value: GtfsRealtime.TranslatedString?): String =
        if (value == null || value.translationList.isEmpty()) ""
        else value.getTranslation(0).text
}
