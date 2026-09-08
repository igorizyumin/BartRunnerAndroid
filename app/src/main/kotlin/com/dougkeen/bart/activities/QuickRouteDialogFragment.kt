package com.dougkeen.bart.activities

import android.content.Intent
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair

class QuickRouteDialogFragment : AbstractRouteSelectionFragment(R.string.quick_departure_lookup) {
    companion object {
        const val TAG = "QUICK_ROUTE_DIALOG_FRAGMENT_TAG"
    }

    override fun onOkButtonClick(origin: Station, destination: Station?) {
        startActivity(Intent(requireActivity(), ViewDeparturesActivity::class.java).apply {
            RouteArguments.putRoute(this, StationPair(origin, destination))
        })
    }
}
