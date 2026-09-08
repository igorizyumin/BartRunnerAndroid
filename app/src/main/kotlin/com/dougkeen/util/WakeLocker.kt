package com.dougkeen.util

import android.content.Context
import android.os.PowerManager

object WakeLocker {
    private var wakeLock: PowerManager.WakeLock? = null

    @JvmStatic
    fun acquire(context: Context) {
        release()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BartRunner:Alarm",
        ).also { it.acquire(60_000L) }
    }

    @JvmStatic
    fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
