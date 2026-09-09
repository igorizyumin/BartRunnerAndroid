package `in`.izyum.bart.data

import android.content.Context
import androidx.core.content.edit

/** Stores the rider category used when displaying fares. */
object FareDiscountPreferences {
    private const val PREFERENCES_NAME = "fare_preferences"
    private const val RIDER_CATEGORY_ID = "rider_category_id"

    fun getRiderCategoryId(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(RIDER_CATEGORY_ID, null)
            ?.takeIf { it.isNotEmpty() }

    fun setRiderCategoryId(context: Context, riderCategoryId: String?) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit {
                if (riderCategoryId.isNullOrEmpty()) remove(RIDER_CATEGORY_ID)
                else putString(RIDER_CATEGORY_ID, riderCategoryId)
            }
    }
}
