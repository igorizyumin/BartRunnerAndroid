package com.dougkeen.bart.activities

import androidx.appcompat.app.AppCompatActivity
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.model.TimeSource
import java.util.concurrent.TimeUnit

abstract class AbstractViewActivity : AppCompatActivity() {
    protected fun activityTimeSource(): TimeSource =
        (application as BartRunnerApplication).timeSource

    override fun onStart() {
        super.onStart()
        val application = application as BartRunnerApplication
        val lastActivity = application.activityTimestamp
        val timeDifference = activityTimeSource().nowMillis() - lastActivity
        if (lastActivity > 0
            && TimeUnit.MILLISECONDS.toHours(timeDifference) >= MAXIMUM_IDLE_HOURS
        ) {
            finish()
        }
    }

    private companion object {
        const val MAXIMUM_IDLE_HOURS = 3L
    }
}
