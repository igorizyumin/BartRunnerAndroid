package com.dougkeen.bart.networktasks;

import com.dougkeen.bart.model.Station;

import java.io.IOException;

public abstract class GetRouteFareTask extends
        NetworkTask<GetRouteFareTask.Params, Integer, String> {

    private Exception mException;

    @Override
    protected String doInBackground(Params... paramsArray) {
        Params params = paramsArray[0];
        if (isCancelled()) {
            return null;
        }
        try {
            return GtfsStaticData.get().getFare(params.origin,
                    params.destination);
        } catch (IOException e) {
            mException = new Exception("Could not load BART fare", e);
            return null;
        }
    }

    public static class Params {
        public Params(Station origin, Station destination) {
            this.origin = origin;
            this.destination = destination;
        }

        public final Station origin;
        public final Station destination;
    }

    @Override
    protected void onPostExecute(String result) {
        if (result != null) {
            onResult(result);
        } else {
            onError(mException);
        }
    }

    public abstract void onResult(String fare);

    public abstract void onError(Exception exception);
}
