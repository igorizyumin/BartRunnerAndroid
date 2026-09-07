package com.dougkeen.bart.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.activities.RouteArguments;
import com.dougkeen.bart.activities.ViewDeparturesActivity;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.platform.DepartureAlarmScheduler;
import com.dougkeen.util.WakeLocker;

public class AlarmBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        BartRunnerApplication application = (BartRunnerApplication) context
                .getApplicationContext();
        final Departure boardedDeparture = application.getFollowedTripRepository()
                .getFollowedDeparture();
        if (boardedDeparture == null) {
            // Nothing to notify about
            return;
        }

        WakeLocker.acquire(context);

        application.getAlarmController().requestRingtone();

        Intent targetIntent = new Intent(context, ViewDeparturesActivity.class);
        RouteArguments.putTrip(targetIntent, boardedDeparture.getStationPair(),
                boardedDeparture.getIdentity(), RouteArguments.MODE_FOLLOWED);
        targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(targetIntent);

        DepartureAlarmScheduler alarmScheduler = application.getFollowedTripRepository()
                .getAlarmScheduler();
        if (alarmScheduler != null) {
            alarmScheduler.notifyAlarmHasBeenHandled();
        }
    }

}
