package com.dougkeen.bart.networktasks;

import com.dougkeen.bart.model.ScheduleInformation;
import com.dougkeen.bart.model.StationPair;

import java.io.IOException;

public abstract class GetScheduleInformationTask extends
        NetworkTask<StationPair, Integer, ScheduleInformation> {

    private Exception mException;

    @Override
    protected ScheduleInformation doInBackground(StationPair... paramsArray) {
        StationPair params = paramsArray[0];
        if (isCancelled() || params.getOrigin() == null
                || params.getDestination() == null) {
            return null;
        }
        try {
            return GtfsStaticData.get().getSchedule(params.getOrigin(),
                    params.getDestination());
        } catch (IOException e) {
            mException = new Exception("Could not load BART schedule", e);
            return null;
        }
    }

    @Override
    protected void onPostExecute(ScheduleInformation result) {
        if (result != null) {
            onResult(result);
        } else {
            onError(mException);
        }
    }

    public abstract void onResult(ScheduleInformation result);

    public abstract void onError(Exception exception);
}
