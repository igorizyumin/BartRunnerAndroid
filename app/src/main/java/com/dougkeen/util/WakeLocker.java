package com.dougkeen.util;

import android.content.Context;
import android.os.PowerManager;

import com.dougkeen.bart.model.Constants;

public abstract class WakeLocker {
    private static PowerManager.WakeLock wakeLock;

    public static void acquire(Context ctx) {
        if (wakeLock != null)
            wakeLock.release();

        PowerManager pm = (PowerManager) ctx
                .getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "BartRunner:WakeLock");
        wakeLock.acquire(60_000L);
    }

    public static void release() {
        if (wakeLock != null)
            wakeLock.release();
        wakeLock = null;
    }
}
