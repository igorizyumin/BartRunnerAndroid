package com.dougkeen.bart.activities

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair

class AddRouteDialogFragment : AbstractRouteSelectionFragment(R.string.add_route) {
    companion object {
        const val TAG = "ADD_ROUTE_DIALOG_FRAGMENT_TAG"
    }

    override fun onStart() {
        super.onStart()
        requireDialog().findViewById<View>(R.id.return_checkbox).visibility = View.VISIBLE
    }

    override fun onOkButtonClick(origin: Station, destination: Station?) {
        val activity = requireActivity() as RoutesListActivity
        activity.addFavorite(StationPair(origin, destination))

        if (destination != null && requireDialog().findViewById<CheckBox>(R.id.return_checkbox).isChecked) {
            activity.addFavorite(StationPair(destination, origin))
        }
        dismiss()
    }
}
