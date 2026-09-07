package com.dougkeen.bart.activities;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.appcompat.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import com.dougkeen.bart.R;
import com.dougkeen.bart.model.Station;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractRouteSelectionFragment extends DialogFragment {

    private static final String KEY_LAST_SELECTED_DESTINATION = "lastSelectedDestination";
    private static final String KEY_LAST_SELECTED_ORIGIN = "lastSelectedOrigin";
    protected String mTitle;
    private final int mTitleResource;

    public AbstractRouteSelectionFragment(int titleResource) {
        super();
        mTitleResource = titleResource;
    }

    @Override
    public void setArguments(Bundle args) {
        super.setArguments(args);
        if (args != null && args.containsKey("title"))
            mTitle = args.getString("title");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowsDialog(true);
    }

    @Override
    public void onStart() {
        super.onStart();

        SharedPreferences preferences = getActivity().getPreferences(
                Context.MODE_PRIVATE);

        final int lastSelectedOriginPosition = preferences.getInt(
                KEY_LAST_SELECTED_ORIGIN, 0);
        final int lastSelectedDestinationPosition = preferences.getInt(
                KEY_LAST_SELECTED_DESTINATION, 1);

        final Dialog dialog = getDialog();
        final FragmentActivity activity = getActivity();

        ArrayAdapter<Station> originSpinnerAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item,
                Station.getStationList());
        originSpinnerAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        final Spinner originSpinner = (Spinner) dialog
                .findViewById(R.id.origin_spinner);
        originSpinner.setAdapter(originSpinnerAdapter);
        originSpinner.setSelection(lastSelectedOriginPosition);

        List<Station> stations = Station.getStationList();
        List<String> destinationNames = new ArrayList<>();
        for (Station station : stations) {
            destinationNames.add(station.name);
        }
        destinationNames.add(activity.getString(R.string.any_destination));

        ArrayAdapter<String> destinationSpinnerAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item,
                destinationNames);
        destinationSpinnerAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        final Spinner destinationSpinner = (Spinner) dialog
                .findViewById(R.id.destination_spinner);
        destinationSpinner.setAdapter(destinationSpinnerAdapter);
        destinationSpinner.setSelection(lastSelectedDestinationPosition);

        final ImageButton swapButton = (ImageButton) dialog.findViewById(R.id.swap_button);
        swapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int destinationSelection = destinationSpinner.getSelectedItemPosition();
                if (destinationSelection >= Station.getStationList().size()) {
                    return;
                }
                destinationSpinner.setSelection(originSpinner.getSelectedItemPosition());
                originSpinner.setSelection(destinationSelection);
            }
        });

        ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> handleOkClick());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final FragmentActivity activity = getActivity();
        if (mTitle == null) {
            mTitle = activity.getString(mTitleResource);
        }

        @SuppressLint("InflateParams")
        final View dialogView = activity.getLayoutInflater()
                .inflate(R.layout.route_form, null /* root */);

        return new AlertDialog.Builder(activity)
                .setTitle(mTitle)
                .setCancelable(true)
                .setView(dialogView)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int whichButton) {
                                dialog.cancel();
                            }
                        }).create();
    }

    protected void handleOkClick() {
        final Dialog dialog = getDialog();
        final Spinner originSpinner = (Spinner) dialog
                .findViewById(R.id.origin_spinner);
        final Spinner destinationSpinner = (Spinner) dialog
                .findViewById(R.id.destination_spinner);

        Station origin = (Station) originSpinner.getSelectedItem();
        List<Station> stations = Station.getStationList();
        Station destination = destinationSpinner.getSelectedItemPosition()
                < stations.size()
                ? stations.get(destinationSpinner.getSelectedItemPosition())
                : null;
        // TODO(fuegofro) - convert these toasts to error messages on the dialog.
        if (origin == null) {
            Toast.makeText(dialog.getContext(),
                    com.dougkeen.bart.R.string.error_null_origin,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (destination != null && origin.equals(destination)) {
            Toast.makeText(
                    dialog.getContext(),
                    com.dougkeen.bart.R.string.error_matching_origin_and_destination,
                    Toast.LENGTH_LONG).show();
            return;
        }

        final Editor prefsEditor = getActivity().getPreferences(
                Context.MODE_PRIVATE).edit();
        prefsEditor.putInt(KEY_LAST_SELECTED_ORIGIN,
                originSpinner.getSelectedItemPosition());
        prefsEditor.putInt(KEY_LAST_SELECTED_DESTINATION,
                destinationSpinner.getSelectedItemPosition());
        prefsEditor.apply();

        onOkButtonClick(origin, destination);
        dismiss();
    }

    abstract protected void onOkButtonClick(Station origin, Station destination);
}
