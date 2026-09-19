package `in`.izyum.bart.presentation

import android.content.Context
import android.text.format.DateFormat
import `in`.izyum.bart.R
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import java.util.Date
import kotlin.math.abs

/** Android-facing formatting for departure text shown by the UI. */
object DepartureTextFormatter {
    data class ScheduleDetails(
        val scheduledTime: String? = null,
        val actualTime: String? = null,
        val actualLabel: String? = null,
        val showActualIcon: Boolean = false,
        val showScheduleOnlyIcon: Boolean = false,
        val predictionLabel: String? = null,
        val isPositiveDelay: Boolean = false,
    ) {
        fun isNotBlank(): Boolean = scheduledTime != null
            || actualTime != null
            || actualLabel != null
            || predictionLabel != null
    }

    @JvmStatic
    fun estimatedArrivalTime(context: Context, departure: Departure): String {
        if (!departure.hasAnyArrivalEstimate()) return ""
        return formatTime(timeFormatter(context), departure.getEstimatedArrivalTime())
    }

    @JvmStatic
    fun estimatedArrivalTime(context: Context, itinerary: Itinerary): String =
        if (!itinerary.hasAnyArrivalEstimate()) ""
        else formatTime(timeFormatter(context), itinerary.getEstimatedArrivalTime())

    @JvmStatic
    fun estimatedDepartureTime(context: Context, departure: Departure): String {
        if (departure.getMeanEstimate() <= 0) return ""
        return formatTime(timeFormatter(context), departure.getMeanEstimate())
    }

    @JvmStatic
    fun estimatedDepartureTime(context: Context, itinerary: Itinerary): String =
        if (itinerary.getInitialDepartureTime() <= 0L) ""
        else formatTime(timeFormatter(context), itinerary.getInitialDepartureTime())

    @JvmStatic
    fun legScheduleDetails(context: Context, leg: TripLeg): String {
        return scheduleDetails(
            context,
            leg.scheduledDepartureTime,
            leg.departureTime,
            leg.departureSource,
        )
    }

    @JvmStatic
    fun departureSchedulePresentation(context: Context, departure: Departure): ScheduleDetails {
        val leg = departure.tripLegs.firstOrNull() ?: return ScheduleDetails()
        return legSchedulePresentation(context, leg)
    }

    @JvmStatic
    fun legSchedulePresentation(context: Context, leg: TripLeg): ScheduleDetails = schedulePresentation(
        context,
        leg.scheduledDepartureTime,
        leg.departureTime,
        leg.departureSource,
    )

    @JvmStatic
    fun stopSchedulePresentation(
        context: Context,
        stop: TripStop,
        departure: Boolean,
    ): ScheduleDetails = schedulePresentation(
        context,
        if (departure) stop.scheduledDepartureTime else stop.scheduledArrivalTime,
        if (departure) stop.departureTime else stop.arrivalTime,
        if (departure) stop.departureSource else stop.arrivalSource,
    )

    private fun schedulePresentation(
        context: Context,
        scheduled: Long,
        effective: Long,
        source: PredictionSource,
    ): ScheduleDetails {
        if (scheduled <= 0L) {
            return when (source) {
                PredictionSource.ESTIMATE -> ScheduleDetails(
                    predictionLabel = context.getString(R.string.prediction_estimated),
                )
                PredictionSource.REALTIME -> ScheduleDetails(
                    showActualIcon = true,
                    predictionLabel = context.getString(R.string.prediction_label),
                )
                else -> ScheduleDetails()
            }
        }
        val scheduledText = formatTime(timeFormatter(context), scheduled)
        return when (source) {
            PredictionSource.REALTIME -> {
                val delay = if (effective > 0L) {
                    ((effective - scheduled) / 1000L).toInt()
                } else {
                    null
                }
                val displayedDelay = delay?.takeIf { abs(it) >= 45 }
                ScheduleDetails(
                    scheduledTime = effective.takeIf { it > 0L }
                        ?.let { formatTime(timeFormatter(context), it) }
                        ?: scheduledText,
                    actualLabel = displayedDelay?.let(::formatSignedMinutes),
                    showActualIcon = displayedDelay != null,
                    isPositiveDelay = displayedDelay?.let { it > 0 } == true,
                )
            }
            PredictionSource.ESTIMATE -> {
                if (effective > 0L && effective != scheduled) {
                    ScheduleDetails(
                        scheduledTime = scheduledText,
                        actualTime = formatTime(timeFormatter(context), effective),
                        actualLabel = context.getString(R.string.estimated_label),
                        showActualIcon = true,
                    )
                } else {
                    ScheduleDetails(
                        scheduledTime = scheduledText,
                        actualLabel = context.getString(R.string.estimated_label),
                    )
                }
            }
            PredictionSource.SCHEDULE, PredictionSource.UNKNOWN ->
                ScheduleDetails(
                    scheduledTime = scheduledText,
                    showScheduleOnlyIcon = source == PredictionSource.SCHEDULE,
                )
        }
    }

    private fun scheduleDetails(
        context: Context,
        scheduled: Long,
        effective: Long,
        source: PredictionSource,
    ): String {
        if (scheduled <= 0L) {
            return when (source) {
                PredictionSource.ESTIMATE -> context.getString(R.string.prediction_estimated)
                PredictionSource.REALTIME -> context.getString(R.string.prediction_realtime)
                else -> ""
            }
        }
        val scheduledText = formatTime(timeFormatter(context), scheduled)
        return when (source) {
            PredictionSource.REALTIME -> {
                val delay = if (effective > 0L) {
                    ((effective - scheduled) / 1000L).toInt()
                } else {
                    null
                }
                val realtimeText = effective.takeIf { it > 0L }
                    ?.let { formatTime(timeFormatter(context), it) }
                    ?: scheduledText
                if (delay == null || abs(delay) < 45) {
                    context.getString(R.string.scheduled_only, realtimeText)
                } else {
                    context.getString(
                        R.string.scheduled_realtime_adjusted,
                        realtimeText,
                        formatSignedMinutes(delay),
                    )
                }
            }
            PredictionSource.ESTIMATE -> {
                if (effective > 0L && effective != scheduled) {
                    context.getString(
                        R.string.scheduled_estimated,
                        scheduledText,
                        formatTime(timeFormatter(context), effective),
                    )
                } else {
                    context.getString(R.string.scheduled_estimated_only, scheduledText)
                }
            }
            PredictionSource.SCHEDULE -> context.getString(R.string.scheduled_only, scheduledText)
            PredictionSource.UNKNOWN -> context.getString(R.string.scheduled_only, scheduledText)
        }
    }

    @JvmStatic
    fun countdown(context: Context, departure: Departure, timeSource: TimeSource): String =
        countdown(context, departure, timeSource.nowMillis())

    @JvmStatic
    fun countdown(context: Context, departure: Departure, nowMillis: Long): String {
        val arrivalTime = departure.getInitialArrivalTime(pessimistic = true)
        val departureTime = departure.getInitialDepartureTime(pessimistic = true)
        val secondsLeft = (arrivalTime - nowMillis) / 1000L
        return when {
            departure.isCanceled() -> context.getString(R.string.departure_canceled)
            nowMillis >= departureTime -> context.getString(R.string.departed)
            nowMillis >= arrivalTime -> context.getString(R.string.departure_at_station)
            else -> context.getString(
                R.string.departure_countdown,
                DurationTextFormatter.clock(secondsLeft),
            )
        }
    }

    @JvmStatic
    fun trainLengthAndPlatform(context: Context, departure: Departure): String {
        val length = departure.trainLength
        val platform = departure.platform
        if (length.isNullOrBlank()) {
            return if (platform.isNullOrBlank()) "" else context.getString(R.string.platform, platform)
        }
        if (platform.isNullOrBlank()) return context.getString(R.string.train_length, length)
        return context.getString(R.string.train_length_platform, length, platform)
    }

    @JvmStatic
    fun formatTime(context: Context, millis: Long): String = formatTime(timeFormatter(context), millis)

    private fun formatTime(formatter: java.text.DateFormat, millis: Long): String =
        formatter.format(Date(millis))

    private fun formatSignedMinutes(delaySeconds: Int): String {
        val roundedMinutes = kotlin.math.round(delaySeconds / 60.0).toInt()
        return "${roundedMinutes}m"
    }

    private fun timeFormatter(context: Context): java.text.DateFormat =
        DateFormat.getTimeFormat(context)
}
