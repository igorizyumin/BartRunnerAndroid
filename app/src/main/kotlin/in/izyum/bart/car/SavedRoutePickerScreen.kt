package `in`.izyum.bart.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import `in`.izyum.bart.BartRunnerApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android Auto Screen providing a Saved Route Picker.
 * Displays user's favorite stations / commutes for fast 1-tap access while driving.
 */
class SavedRoutePickerScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var observerJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        val app = carContext.applicationContext as? BartRunnerApplication ?: return
        observerJob?.cancel()
        observerJob = lifecycleScope.launch {
            app.favoritesRepository.uiState.collectLatest {
                runCatching { invalidate() }
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        observerJob?.cancel()
        observerJob = null
    }

    override fun onGetTemplate(): Template {
        return runCatching { buildTemplate() }.getOrElse { buildFallbackTemplate() }
    }

    private fun buildTemplate(): Template {
        val app = carContext.applicationContext as? BartRunnerApplication
        val favoritesState = app?.favoritesRepository?.uiState?.value
        val favorites = favoritesState?.favorites.orEmpty()

        val listBuilder = ItemList.Builder()

        if (favorites.isEmpty()) {
            val emptyTitle = if (favoritesState?.isLoading == true) "Loading Saved Commutes..." else "No Favorite Commutes Saved"
            val emptySubtitle = if (favoritesState?.isLoading == true) {
                "Fetching saved favorites from storage..."
            } else {
                "Add favorite routes on your phone to quickly access them in Android Auto."
            }
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(emptyTitle)
                    .addText(emptySubtitle)
                    .build(),
            )
        } else {
            // Android Auto ListTemplate caps list to 6 items max per template guidelines
            val displayFavorites = favorites.take(MAX_DISPLAY_FAVORITES)
            for (favorite in displayFavorites) {
                val origin = favorite.origin ?: continue

                val originName = origin.getName()
                val destName = favorite.destination?.getName() ?: "All Destinations"
                val subtitle = "${origin.abbreviation} → ${favorite.destination?.abbreviation ?: "ALL"}"

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("$originName → $destName")
                        .addText(subtitle)
                        .setOnClickListener {
                            screenManager.push(ActiveDepartureScreen(carContext, favorite))
                        }
                        .build(),
                )
            }
        }

        val header = Header.Builder()
            .setTitle("Saved Route Picker")
            .setStartHeaderAction(Action.APP_ICON)
            .addEndHeaderAction(
                Action.Builder()
                    .setTitle("Refresh")
                    .setOnClickListener { runCatching { invalidate() } }
                    .build(),
            )
            .addEndHeaderAction(
                Action.Builder()
                    .setTitle("Settings")
                    .setOnClickListener {
                        screenManager.push(CarSettingsScreen(carContext))
                    }
                    .build(),
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun buildFallbackTemplate(): Template {
        val header = Header.Builder()
            .setTitle("Saved Route Picker")
            .setStartHeaderAction(Action.APP_ICON)
            .addEndHeaderAction(
                Action.Builder()
                    .setTitle("Refresh")
                    .setOnClickListener { runCatching { invalidate() } }
                    .build(),
            )
            .build()

        val listBuilder = ItemList.Builder().addItem(
            Row.Builder()
                .setTitle("Unable to load saved routes")
                .addText("Tap Refresh to try again.")
                .build(),
        )

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }

    companion object {
        private const val MAX_DISPLAY_FAVORITES = 6
    }
}
