package `in`.izyum.bart.platform

import android.app.AlarmManager
import android.content.Context
import android.os.Build

object ExactAlarmPermission {
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)
            ?.canScheduleExactAlarms() == true
    }
}
