package `in`.izyum.bart.activities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.izyum.bart.BartRunnerApplication
import `in`.izyum.bart.backend.AlertProjection
import `in`.izyum.bart.model.Alert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Observes the latest successfully projected BART service alerts. */
class ServiceAlertsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BartRunnerApplication
    private val _alerts = MutableStateFlow<Alert.AlertList?>(null)

    val alerts: StateFlow<Alert.AlertList?> = _alerts.asStateFlow()

    init {
        viewModelScope.launch {
            val projection = AlertProjection()
            app.transitRepository.projectedState(
                projection::project,
                projection::areEquivalent,
            ).collectLatest { result ->
                result.getOrNull()?.let { alerts ->
                    _alerts.value = alerts
                }
            }
        }
    }
}
