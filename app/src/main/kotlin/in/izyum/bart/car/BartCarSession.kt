package `in`.izyum.bart.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Manages the lifecycle of the Android Auto session.
 * Instantiates the initial Saved Route Picker screen when launched.
 */
class BartCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return SavedRoutePickerScreen(carContext)
    }
}
