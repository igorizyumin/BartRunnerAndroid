package com.dougkeen.bart.activities;

import androidx.appcompat.app.AppCompatActivity;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.model.TimeSource;

import java.util.concurrent.TimeUnit;

public abstract class AbstractViewActivity extends AppCompatActivity {

    private static final int MAXIMUM_IDLE_HOURS = 3;
    protected TimeSource activityTimeSource() {
        return ((BartRunnerApplication) getApplication()).getTimeSource();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BartRunnerApplication application = (BartRunnerApplication) getApplication();
        long lastActivity = application.getActivityTimestamp();
        long currentTime = activityTimeSource().nowMillis();
        long timeDifference = currentTime - lastActivity;
        if (lastActivity > 0
                && TimeUnit.MILLISECONDS.toHours(timeDifference) >= MAXIMUM_IDLE_HOURS) {
            finish();
        }
    }
}
