package com.dougkeen.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.model.Departure
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

    fun followTrip(departure: Departure): String {
        followedTripRepository.setFollowedDeparture(departure)
        return BoardedDepartureService.ACTION_FOLLOW_DEPARTURE
    }

    fun updateFollowedTrip(departure: Departure) {
        if (followedTripRepository.getFollowedDeparture() != null) {
            followedTripRepository.setFollowedDeparture(departure)
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
