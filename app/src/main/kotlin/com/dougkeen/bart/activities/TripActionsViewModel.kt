package com.dougkeen.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.services.BoardedDepartureService

/** Owns user decisions that mutate or command the followed-trip service. */
class TripActionsViewModel(application: Application) :
    AndroidViewModel(application) {
    private val followedTripRepository = (application as BartRunnerApplication)
        .followedTripRepository

    fun getFollowedDeparture(): Departure? = followedTripRepository.getFollowedDeparture()

    fun isFollowing(departure: Departure): Boolean =
        followedTripRepository.getFollowedDeparture() == departure

    fun isAlarmPending(): Boolean = followedTripRepository.getAlarmScheduler()?.isPending == true

    fun getAlarmLeadTimeMinutes(): Int =
        followedTripRepository.getAlarmScheduler()?.leadTimeMinutes ?: 0

    fun followTrip(departure: Departure, passengerDestination: Station? = null): String {
        followedTripRepository.setFollowedDeparture(
            prepareDepartureForFollowing(departure, passengerDestination),
        )
        return BoardedDepartureService.ACTION_FOLLOW_DEPARTURE
    }

    fun updateFollowedTrip(departure: Departure) {
        followedTripRepository.getFollowedDeparture()?.let { current ->
            followedTripRepository.setFollowedDeparture(
                prepareDepartureForFollowing(departure, current.passengerDestination),
            )
        }
    }

    fun cancelAlarm(): String {
        followedTripRepository.getAlarmScheduler()?.cancel()
        return BoardedDepartureService.ACTION_CANCEL_ALARM
    }

    fun clearTrip(): String {
        followedTripRepository.clearFollowedDeparture()
        return BoardedDepartureService.ACTION_CLEAR_DEPARTURE
    }

    fun setAlarm(leadTimeMinutes: Int) {
        followedTripRepository.getAlarmScheduler()?.setUp(leadTimeMinutes)
    }
}

/** Ensures a departure followed from trip details has the destination required by notifications. */
internal fun prepareDepartureForFollowing(
    departure: Departure,
    passengerDestination: Station? = null,
): Departure = departure.withPassengerDestination(
    passengerDestination ?: departure.passengerDestination ?: departure.trainDestination,
)
