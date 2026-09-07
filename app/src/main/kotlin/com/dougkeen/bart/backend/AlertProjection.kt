package com.dougkeen.bart.backend

import com.dougkeen.bart.model.Alert
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex
import com.google.transit.realtime.GtfsRealtime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Converts the latest alert feed into the app's alert model. */
class AlertProjection : TransitProjection<Alert.AlertList> {
    override fun project(snapshot: TransitFeedSnapshot): Alert.AlertList {
        val format = DateTimeFormatter.ofLocalizedDateTime(
            FormatStyle.SHORT,
            FormatStyle.SHORT
        ).withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())
        val index: GtfsRealtimeFeedIndex = snapshot.getAlertIndex()
        val alerts = mutableListOf<Alert>()
        for (entity in index.alertEntities) {
            if (!entity.hasAlert()) {
                continue
            }
            val source = entity.alert
            var postedTime: String? = null
            var expiresTime: String? = null
            if (source.activePeriodList.isNotEmpty()) {
                val period = source.getActivePeriod(0)
                if (period.hasStart()) {
                    postedTime = format.format(Instant.ofEpochSecond(period.start))
                }
                if (period.hasEnd()) {
                    expiresTime = format.format(Instant.ofEpochSecond(period.end))
                }
            }
            alerts += Alert(
                id = entity.id,
                type = if (source.hasEffect()) source.effect.name else "",
                description = text(
                    if (source.hasHeaderText()) source.headerText else null,
                    if (source.hasDescriptionText()) source.descriptionText else null
                ),
                postedTime = postedTime,
                expiresTime = expiresTime
            )
        }
        return Alert.AlertList(alerts, alerts.isEmpty())
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
            if (left.id != right.id
                || left.type != right.type
                || left.description != right.description
                || left.postedTime != right.postedTime
                || left.expiresTime != right.expiresTime
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
