package com.dougkeen.bart.presentation

import android.content.Context
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.PredictionSource
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.model.TripLeg
import com.dougkeen.bart.model.TripStop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Android-facing formatting for departure text shown by the UI. */
object DepartureTextFormatter {
    data class ScheduleDetails(
        val scheduledTime: String? = null,
        val actualTime: String? = null,
        val actualLabel: String? = null,
        val showActualIcon: Boolean = false,
        val predictionLabel: String? = null,
    ) {
        fun isNotBlank(): Boolean = scheduledTime != null
            || actualTime != null
            || actualLabel != null
            || predictionLabel != null
    }

    @JvmStatic
    fun transferDetails(context: Context, departure: Departure): String {
        if (!departure.hasTransfers()) return ""
        val timeFormat = timeFormatter(context)
        return departure.tripLegs.mapIndexed { index, leg ->
            val lineName = leg.line?.getDisplayName() ?: context.getString(R.string.train)
            val departureTime = if (leg.departureTime <= 0) {
                context.getString(R.string.trip_no_departure_scheduled)
            } else {
                formatTime(timeFormat, leg.departureTime)
            }
            val route = if (leg.origin != null && leg.destination != null) {
                context.getString(
                    R.string.route_title_arrow,
                    leg.origin.shortName,
                    leg.destination.shortName,
                )
            } else {
                null
            }
            val legText = if (route == null) {
                context.getString(R.string.transfer_leg, lineName, departureTime)
            } else {
                context.getString(R.string.transfer_leg_route, lineName, departureTime, route)
            }
            buildString {
                if (index > 0) append('\n')
                append(legText)
                if (leg.arrivalTime > 0) {
                    append(context.getString(
                        R.string.transfer_leg_arrival,
                        formatTime(timeFormat, leg.arrivalTime),
                    ))
                }
                val nextLeg = departure.tripLegs.getOrNull(index + 1)
                if (nextLeg != null && leg.arrivalTime > 0 && nextLeg.departureTime > 0) {
                    val safeMargin = (nextLeg.departureTime - leg.arrivalTime).coerceAtLeast(0)
                    val marginMinutes = safeMargin / 60000L
                    val marginSeconds = (safeMargin % 60000L) / 1000L
                    val marginText = when {
                        marginMinutes > 0 && marginSeconds > 0 -> context.getString(
                            R.string.connection_margin_minutes_seconds,
                            marginMinutes,
                            marginSeconds,
                        )
                        marginMinutes > 0 -> context.getString(
                            R.string.connection_margin_minutes,
                            marginMinutes,
                        )
                        else -> context.getString(R.string.connection_margin_seconds, marginSeconds)
                    }
                    append(context.getString(
                        R.string.transfer_connection_separator,
                        context.getString(R.string.transfer_connection_margin, marginText),
                    ))
                }
            }
        }.joinToString("\n")
    }

    @JvmStatic
    fun estimatedArrivalMinutesLeft(
        context: Context,
        departure: Departure,
        timeSource: TimeSource,
    ): String = estimatedArrivalMinutesLeft(context, departure, timeSource.nowMillis())

    @JvmStatic
    fun estimatedArrivalMinutesLeft(context: Context, departure: Departure, nowMillis: Long): String {
        if (!departure.hasAnyArrivalEstimate()) return context.getString(R.string.estimated_arrival_unknown)
        val minutesLeft = departure.getEstimatedArrivalMinutesLeft(nowMillis)
        return when {
            departure.isCanceled() -> ""
            minutesLeft < 0 -> context.getString(R.string.arrived_at_destination)
            minutesLeft == 0L -> context.getString(
                R.string.arrives_around_less_than_minute,
                estimatedArrivalTime(context, departure, false),
            )
            minutesLeft == 1L -> context.getString(
                R.string.arrives_around_one_minute,
                estimatedArrivalTime(context, departure, false),
            )
            else -> context.getString(
                R.string.arrives_around_minutes,
                estimatedArrivalTime(context, departure, false),
                minutesLeft,
            )
        }
    }

    @JvmStatic
    fun estimatedArrivalTime(context: Context, departure: Departure): String =
        estimatedArrivalTime(context, departure, false)

    @JvmStatic
    fun estimatedArrivalTime(context: Context, departure: Departure, compact: Boolean): String {
        if (!departure.hasAnyArrivalEstimate()) return ""
        return formatTime(timeFormatter(context), departure.getEstimatedArrivalTime())
    }

    @JvmStatic
    fun estimatedDepartureTime(context: Context, departure: Departure): String =
        estimatedDepartureTime(context, departure, false)

    @JvmStatic
    fun estimatedDepartureTime(context: Context, departure: Departure, compact: Boolean): String {
        if (departure.getMeanEstimate() <= 0) return ""
        return formatTime(timeFormatter(context), departure.getMeanEstimate())
    }

    @JvmStatic
    fun departureScheduleDetails(context: Context, departure: Departure): String {
        val leg = departure.tripLegs.firstOrNull() ?: return ""
        return legScheduleDetails(context, leg)
    }

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
    fun stopScheduleDetails(
        context: Context,
        stop: TripStop,
        departure: Boolean,
    ): String = scheduleDetails(
        context,
        if (departure) stop.scheduledDepartureTime else stop.scheduledArrivalTime,
        if (departure) stop.departureTime else stop.arrivalTime,
        if (departure) stop.departureSource else stop.arrivalSource,
    )

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
                ScheduleDetails(
                    scheduledTime = scheduledText,
                    actualLabel = delay?.takeIf { it != 0 }?.let(::formatSignedMinutes),
                    showActualIcon = true,
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
                ScheduleDetails(scheduledTime = scheduledText)
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
                if (delay == null || delay == 0) {
                    context.getString(R.string.scheduled_realtime, scheduledText)
                } else {
                    context.getString(
                        R.string.scheduled_delay,
                        scheduledText,
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
        val secondsLeft = departure.getMeanSecondsLeft(
            departure.minEstimate,
            departure.maxEstimate,
            nowMillis,
        )
        return when {
            departure.isCanceled() -> context.getString(R.string.departure_canceled)
            departure.hasDeparted(nowMillis) -> if (
                departure.origin?.longStationLinger == true && departure.beganAsDeparted()
            ) {
                context.getString(R.string.departure_at_station)
            } else {
                context.getString(if (departure.isListedInETDs()) R.string.leaving else R.string.departed)
            }
            else -> context.getString(R.string.departure_countdown, secondsLeft / 60, secondsLeft % 60)
        }
    }

    @JvmStatic
    fun uncertainty(context: Context, departure: Departure, timeSource: TimeSource): String {
        if (departure.hasDeparted(timeSource) || departure.isCanceled()) return ""
        return context.getString(R.string.uncertainty_seconds, departure.getUncertaintySeconds())
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

    @JvmStatic
    fun formatBartScheduleTime(millis: Long): String = DateTimeFormatter.ofPattern("h:mma", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))

    private fun formatTime(formatter: DateTimeFormatter, millis: Long): String =
        formatter.format(Instant.ofEpochMilli(millis))

    private fun formatSignedMinutes(delaySeconds: Int): String {
        val roundedMinutes = kotlin.math.round(delaySeconds / 60.0).toInt()
        return if (roundedMinutes >= 0) "+${roundedMinutes}m" else "${roundedMinutes}m"
    }

    private fun timeFormatter(context: Context): DateTimeFormatter {
        val locale = context.resources.configuration.locales[0]
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
}
