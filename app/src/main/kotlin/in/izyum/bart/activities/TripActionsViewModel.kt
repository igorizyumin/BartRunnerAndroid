package `in`.izyum.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.model.Itinerary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TripActionsUiState(
    val alarmPending: Boolean = false,
    val alarmLeadTimeMinutes: Int = 0,
)

/** Owns user decisions that mutate followed-trip and alarm state. */
class TripActionsViewModel(application: Application) :
    AndroidViewModel(application) {
    private val followedTripRepository = (application as BartRunnerApplication)
        .followedTripRepository
    private val _uiState = MutableStateFlow(readUiState())
    val uiState: StateFlow<TripActionsUiState> = _uiState.asStateFlow()

    fun getFollowedItinerary(): Itinerary? = followedTripRepository.getFollowedItinerary()

    fun isFollowing(itinerary: Itinerary): Boolean =
        followedTripRepository.getFollowedItinerary() == itinerary

    fun isAlarmPending(): Boolean = followedTripRepository.getAlarmScheduler()?.isPending == true

    fun getAlarmLeadTimeMinutes(): Int =
        followedTripRepository.getAlarmScheduler()?.leadTimeMinutes ?: 0

    fun refreshAlarmState() {
        _uiState.value = readUiState()
    }

    fun followTrip(itinerary: Itinerary) {
        followedTripRepository.setFollowedItinerary(itinerary)
    }

    fun updateFollowedTrip(itinerary: Itinerary) {
        followedTripRepository.getFollowedItinerary()?.let {
            followedTripRepository.setFollowedItinerary(itinerary)
        }
    }

    fun cancelAlarm() {
        followedTripRepository.cancelAlarm()
        refreshAlarmState()
    }

    fun clearTrip() {
        followedTripRepository.clearFollowedDeparture()
    }

    fun setAlarm(leadTimeMinutes: Int) {
        followedTripRepository.setAlarm(leadTimeMinutes)
        refreshAlarmState()
    }

    private fun readUiState() = TripActionsUiState(
        alarmPending = isAlarmPending(),
        alarmLeadTimeMinutes = getAlarmLeadTimeMinutes(),
    )
}
