package com.dougkeen.bart.networktasks;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Small executor-backed replacement for the deprecated Android AsyncTask API.
 * Work runs off the main thread and completion callbacks are delivered on it.
 */
public abstract class NetworkTask<Params, Progress, Result> {

    public enum Status { PENDING, RUNNING, FINISHED }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private volatile Status status = Status.PENDING;
    private volatile boolean cancelled;
    private volatile Future<?> future;

    public final NetworkTask<Params, Progress, Result> execute(final Params... params) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("A task can only be executed once");
        }
        status = Status.RUNNING;
        future = EXECUTOR.submit(new Runnable() {
            @Override
            public void run() {
                final Result result = doInBackground(params);
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        status = Status.FINISHED;
                        if (!cancelled) {
                            onPostExecute(result);
                        }
                    }
                });
            }
        });
        return this;
    }

    public final boolean cancel(boolean mayInterruptIfRunning) {
        cancelled = true;
        status = Status.FINISHED;
        Future<?> taskFuture = future;
        return taskFuture == null || taskFuture.cancel(mayInterruptIfRunning);
    }

    public final boolean isCancelled() {
        return cancelled;
    }

    public final Status getStatus() {
        return status;
    }

    protected abstract Result doInBackground(Params... params);

    protected void onPostExecute(Result result) {
        // Optional completion callback.
    }
}
