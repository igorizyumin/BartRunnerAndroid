package com.dougkeen.bart.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.activities.RouteArguments
import com.dougkeen.bart.activities.ViewDeparturesActivity
import com.dougkeen.bart.platform.DepartureAlarmScheduler
import com.dougkeen.util.WakeLocker

class AlarmBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as BartRunnerApplication
        val boardedDeparture = application.followedTripRepository.getFollowedDeparture()
            ?: return

        WakeLocker.acquire(context)
        application.alarmController.requestRingtone()

        val targetIntent = Intent(context, ViewDeparturesActivity::class.java).apply {
            RouteArguments.putTrip(
                this,
                boardedDeparture.getStationPair(),
                boardedDeparture.identity,
                RouteArguments.MODE_FOLLOWED,
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(targetIntent)

        application.followedTripRepository.getAlarmScheduler()
            ?.notifyAlarmHasBeenHandled()
    }
}
