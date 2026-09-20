package `in`.izyum.bart.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Manages the lifecycle of the Android Auto session.
 * SavedRoutePickerScreen serves as the root main menu screen.
 */
class BartCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return SavedRoutePickerScreen(carContext)
    }
}
