package `in`.izyum.bart.data

import android.content.Context
import androidx.core.content.edit

/** Stores user preferences for the user-visible departure alarm. */
object AlarmPreferences {
    private const val NAME = "alarm_preferences"
    private const val VIBRATION_ENABLED = "vibration_enabled"

    fun isVibrationEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(VIBRATION_ENABLED, true)

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(VIBRATION_ENABLED, enabled) }
    }
}
