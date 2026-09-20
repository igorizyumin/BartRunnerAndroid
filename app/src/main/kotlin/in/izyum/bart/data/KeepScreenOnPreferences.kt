package `in`.izyum.bart.data

import android.content.Context
import androidx.core.content.edit

/** Manages persistent preference for keeping screen active during full-screen ride follow. */
object KeepScreenOnPreferences {
    private const val PREFERENCES_NAME = "keep_screen_on_preferences"
    private const val KEEP_SCREEN_ON_KEY = "keep_screen_on"

    fun isKeepScreenOn(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEEP_SCREEN_ON_KEY, false)

    fun setKeepScreenOn(context: Context, keepOn: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEEP_SCREEN_ON_KEY, keepOn) }
    }
}
