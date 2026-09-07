package com.dougkeen.bart.platform;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.receivers.AlarmBroadcastReceiver;
import com.dougkeen.util.Observable;

/** Owns Android alarm scheduling for one followed departure. */
public final class DepartureAlarmScheduler implements AutoCloseable {
    private final Context applicationContext;
    private final AlarmManager alarmManager;
    private final Departure departure;
    private final Observable<Integer> leadTimeMinutes = new Observable<>(0);
    private final Observable<Boolean> pending = new Observable<>(false);

    public DepartureAlarmScheduler(Context context, Departure departure) {
        applicationContext = context.getApplicationContext();
        alarmManager = (AlarmManager) applicationContext
                .getSystemService(Context.ALARM_SERVICE);
        this.departure = departure;
    }

    public int getLeadTimeMinutes() {
        return leadTimeMinutes.getValue();
    }

    public Observable<Integer> getLeadTimeMinutesObservable() {
        return leadTimeMinutes;
    }

    public boolean isPending() {
        return pending.getValue();
    }

    public Observable<Boolean> getPendingObservable() {
        return pending;
    }

    public int getSecondsUntilAlarm() {
        return departure.getMeanSecondsLeft() - getLeadTimeMinutes() * 60;
    }

    public void setUp(int leadTimeMinutes) {
        this.leadTimeMinutes.setValue(leadTimeMinutes);
        pending.setValue(true);
        schedule();
    }

    public void update() {
        if (alarmManager == null) {
            Log.w(Constants.TAG, "No alarm manager available, so alarm will not be updated");
            return;
        }
        if (isPending() && getLeadTimeMinutes() > 0) {
            schedule();
        }
    }

    public void cancel() {
        if (alarmManager != null) {
            alarmManager.cancel(getAlarmIntent());
        }
        pending.setValue(false);
        Log.d(Constants.TAG, "Alarm cancelled");
    }

    public void notifyAlarmHasBeenHandled() {
        pending.setValue(false);
    }

    @Override
    public void close() {
        if (isPending()) {
            cancel();
        }
        leadTimeMinutes.unregisterAllObservers();
        pending.unregisterAllObservers();
    }

    private PendingIntent getAlarmIntent() {
        Intent intent = new Intent(applicationContext, AlarmBroadcastReceiver.class);
        intent.setAction(Constants.ACTION_ALARM);
        return PendingIntent.getBroadcast(applicationContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);
    }

    private long getAlarmClockTime() {
        return departure.getMeanEstimate() - getLeadTimeMinutes() * 60 * 1000L;
    }

    private void schedule() {
        if (alarmManager == null) {
            Log.w(Constants.TAG, "No alarm manager available, so alarm will not be scheduled");
            return;
        }

        long alarmTime = getAlarmClockTime();
        PendingIntent alarmIntent = getAlarmIntent();
        if (alarmTime < System.currentTimeMillis()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime,
                        alarmIntent);
                Log.w(Constants.TAG,
                        "Exact alarm permission is unavailable; using an inexact alarm");
                return;
            }
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime,
                        alarmIntent);
            } catch (SecurityException exception) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime,
                        alarmIntent);
                Log.w(Constants.TAG,
                        "Exact alarm permission is unavailable; using an inexact alarm");
            }
        } else {
            try {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
            } catch (SecurityException exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, alarmTime, alarmIntent);
                Log.w(Constants.TAG,
                        "Exact alarm permission is unavailable; using a regular alarm");
            }
        }

        Log.v(Constants.TAG, "Scheduling alarm for "
                + android.text.format.DateFormat.format("h:mm:ss", alarmTime));
    }
}
