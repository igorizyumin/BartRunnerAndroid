package `in`.izyum.bart.data

import android.content.Context
import androidx.core.content.edit

/** Stores whether followed-trip updates may run while the app is closed. */
object BackgroundPollingPreferences {
    private const val NAME = "background_polling"
    private const val ENABLED = "enabled"
    private const val EXACT_ALARM_PROMPT_SHOWN = "exact_alarm_prompt_shown"

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(ENABLED, enabled) }
    }

    fun hasShownExactAlarmPrompt(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(EXACT_ALARM_PROMPT_SHOWN, false)

    fun markExactAlarmPromptShown(context: Context) {
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(EXACT_ALARM_PROMPT_SHOWN, true) }
    }
}
