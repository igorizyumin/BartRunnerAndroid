package com.dougkeen.bart.activities;

import androidx.appcompat.app.AppCompatActivity;

import com.dougkeen.bart.BartRunnerApplication;

import java.util.concurrent.TimeUnit;

public abstract class AbstractViewActivity extends AppCompatActivity {

    private static final int MAXIMUM_IDLE_HOURS = 3;

    @Override
    protected void onStart() {
        super.onStart();
        BartRunnerApplication application = (BartRunnerApplication) getApplication();
        long lastActivity = application.getActivityTimestamp();
        long currentTime = System.currentTimeMillis();
        long timeDifference = currentTime - lastActivity;
        if (lastActivity > 0
                && TimeUnit.MILLISECONDS.toHours(timeDifference) >= MAXIMUM_IDLE_HOURS) {
            finish();
        }
    }
}
