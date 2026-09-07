package com.dougkeen.bart.activities;

import android.content.Intent;

import com.dougkeen.bart.R;
import com.dougkeen.bart.model.Constants;
import com.dougkeen.bart.model.Station;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.platform.StationPairParcel;

public class QuickRouteDialogFragment extends AbstractRouteSelectionFragment {

    public static final String TAG = "QUICK_ROUTE_DIALOG_FRAGMENT_TAG";

    public QuickRouteDialogFragment() {
        super(R.string.quick_departure_lookup);
    }

    @Override
    protected void onOkButtonClick(Station origin, Station destination) {
        Intent intent = new Intent(getActivity(), ViewDeparturesActivity.class);
        intent.putExtra(Constants.STATION_PAIR_EXTRA, new StationPairParcel(
                new StationPair(origin, destination)));
        startActivity(intent);
    }
}
