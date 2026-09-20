package `in`.izyum.bart.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/**
 * Android Auto Screen providing settings for Android Auto options.
 * Allows configuring the default transfer view setting, default audio guidance,
 * and clearing per-route preference overrides.
 */
class CarSettingsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // 1. Default Transfer View Setting
        val defaultShowTransfers = CarPreferences.getDefaultShowTransfers(carContext)
        val transferSubtitle = if (defaultShowTransfers) {
            "Showing all trains (Direct & Transfers)"
        } else {
            "Direct trains only (Filtering out transfer routes)"
        }

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Default Transfer View Setting")
                .addText(transferSubtitle)
                .setOnClickListener {
                    CarPreferences.setDefaultShowTransfers(carContext, !defaultShowTransfers)
                    invalidate()
                }
                .build(),
        )

        // 2. Default Audio Guidance Setting
        val defaultAudioGuidance = CarPreferences.getDefaultAudioGuidance(carContext)
        val audioSubtitle = if (defaultAudioGuidance) {
            "Enabled (Media ducking and voice alerts on)"
        } else {
            "Disabled (Muted)"
        }

        listBuilder.addItem(
            Row.Builder()
                .setTitle("Default Audio Guidance")
                .addText(audioSubtitle)
                .setOnClickListener {
                    CarPreferences.setDefaultAudioGuidance(carContext, !defaultAudioGuidance)
                    invalidate()
                }
                .build(),
        )

        // 3. Clear Per-Route Overrides
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Reset Per-Route Transfer Settings")
                .addText("Clear saved route-specific transfer view overrides and revert all routes to default.")
                .setOnClickListener {
                    CarPreferences.clearRouteOverrides(carContext)
                    invalidate()
                }
                .build(),
        )

        val header = Header.Builder()
            .setTitle("Android Auto Settings")
            .setStartHeaderAction(Action.BACK)
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }
}
