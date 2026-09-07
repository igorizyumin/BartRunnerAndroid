package com.dougkeen.bart.activities;

import android.content.Intent;

import com.dougkeen.bart.R;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;

public class QuickRouteDialogFragment extends AbstractRouteSelectionFragment {

    public static final String TAG = "QUICK_ROUTE_DIALOG_FRAGMENT_TAG";

    public QuickRouteDialogFragment() {
        super(R.string.quick_departure_lookup);
    }

    @Override
    protected void onOkButtonClick(Station origin, Station destination) {
        Intent intent = new Intent(getActivity(), ViewDeparturesActivity.class);
        RouteArguments.putRoute(intent, new StationPair(origin, destination));
        startActivity(intent);
    }
}
