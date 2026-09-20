package `in`.izyum.bart.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import `in`.izyum.bart.BartRunnerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Entry point for Android Auto / Android for Cars App Library.
 * Connects the car head unit to the BartRunner app session.
 */
class BartCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        val app = applicationContext as? BartRunnerApplication
        if (app != null) {
            app.transitRepository.setAppInForeground(true)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { app.gtfsStaticData.warmUp() }
                runCatching { app.transitRepository.refreshNow() }
            }
        }
        return BartCarSession()
    }
}
