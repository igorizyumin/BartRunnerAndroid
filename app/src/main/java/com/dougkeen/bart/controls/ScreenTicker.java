package com.dougkeen.bart.controls;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

/** A screen-scoped clock that runs only while its lifecycle is started. */
public final class ScreenTicker implements DefaultLifecycleObserver {
    public interface Listener {
        void onTick(long tick);
    }

    private static final long TICK_INTERVAL_MILLIS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean started;
    private long tick;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isStarted()) {
                return;
            }
            listener.onTick(tick++);
            handler.postDelayed(this, TICK_INTERVAL_MILLIS);
        }
    };

    public ScreenTicker(@NonNull Lifecycle lifecycle, @NonNull Listener listener) {
        this.listener = listener;
        lifecycle.addObserver(this);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        started = true;
        handler.removeCallbacks(tickRunnable);
        tickRunnable.run();
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        started = false;
        handler.removeCallbacks(tickRunnable);
    }

    private boolean isStarted() {
        return started;
    }
}
