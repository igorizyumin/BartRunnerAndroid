package com.dougkeen.bart.controls

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** A screen-scoped clock that runs only while its lifecycle is started. */
class ScreenTicker(
    lifecycle: Lifecycle,
    private val listener: Listener,
) : DefaultLifecycleObserver {
    fun interface Listener {
        fun onTick(tick: Long)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var tick = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            listener.onTick(tick++)
            handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        handler.removeCallbacks(tickRunnable)
        tickRunnable.run()
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        handler.removeCallbacks(tickRunnable)
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1000L
    }
}
