package com.dougkeen.bart.activities

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Station

abstract class AbstractRouteSelectionFragment(private val titleResource: Int) : DialogFragment() {
    companion object {
        private const val KEY_LAST_SELECTED_DESTINATION = "lastSelectedDestination"
        private const val KEY_LAST_SELECTED_ORIGIN = "lastSelectedOrigin"
    }

    private var titleText: String? = null

    override fun setArguments(args: Bundle?) {
        super.setArguments(args)
        titleText = args?.getString("title")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showsDialog = true
    }

    override fun onStart() {
        super.onStart()

        val activity = requireActivity()
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        val lastSelectedOriginPosition = preferences.getInt(KEY_LAST_SELECTED_ORIGIN, 0)
        val lastSelectedDestinationPosition = preferences.getInt(KEY_LAST_SELECTED_DESTINATION, 1)
        val dialog = requireDialog()

        val stations = Station.getStationList()
        val originSpinnerAdapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            stations,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val originSpinner = dialog.findViewById<Spinner>(R.id.origin_spinner)
        originSpinner.adapter = originSpinnerAdapter
        originSpinner.setSelection(lastSelectedOriginPosition)

        val destinationNames = stations.map(Station::getName) + activity.getString(R.string.any_destination)
        val destinationSpinnerAdapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            destinationNames,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val destinationSpinner = dialog.findViewById<Spinner>(R.id.destination_spinner)
        destinationSpinner.adapter = destinationSpinnerAdapter
        destinationSpinner.setSelection(lastSelectedDestinationPosition)

        dialog.findViewById<ImageButton>(R.id.swap_button).setOnClickListener {
            val destinationSelection = destinationSpinner.selectedItemPosition
            if (destinationSelection < stations.size) {
                destinationSpinner.setSelection(originSpinner.selectedItemPosition)
                originSpinner.setSelection(destinationSelection)
            }
        }

        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener { handleOkClick() }
    }

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val dialogView = activity.layoutInflater.inflate(R.layout.route_form, null)
        return AlertDialog.Builder(activity)
            .setTitle(titleText ?: activity.getString(titleResource))
            .setCancelable(true)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    protected fun handleOkClick() {
        val dialog = requireDialog()
        val originSpinner = dialog.findViewById<Spinner>(R.id.origin_spinner)
        val destinationSpinner = dialog.findViewById<Spinner>(R.id.destination_spinner)
        val stations = Station.getStationList()
        val origin = originSpinner.selectedItem as? Station
        val destination = stations.getOrNull(destinationSpinner.selectedItemPosition)

        if (origin == null) {
            Toast.makeText(dialog.context, R.string.error_null_origin, Toast.LENGTH_LONG).show()
            return
        }
        if (origin == destination) {
            Toast.makeText(
                dialog.context,
                R.string.error_matching_origin_and_destination,
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        activity?.getPreferences(Context.MODE_PRIVATE)?.edit()
            ?.putInt(KEY_LAST_SELECTED_ORIGIN, originSpinner.selectedItemPosition)
            ?.putInt(KEY_LAST_SELECTED_DESTINATION, destinationSpinner.selectedItemPosition)
            ?.apply()

        onOkButtonClick(origin, destination)
        dismiss()
    }

    protected abstract fun onOkButtonClick(origin: Station, destination: Station?)
}
