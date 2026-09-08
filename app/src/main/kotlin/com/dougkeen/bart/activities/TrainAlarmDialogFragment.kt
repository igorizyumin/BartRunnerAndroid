package com.dougkeen.bart.activities

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R

class TrainAlarmDialogFragment : DialogFragment() {
    companion object {
        const val TAG = "TRAIN_ALARM_DIALOG_FRAGMENT_TAG"
        private const val KEY_LAST_ALARM_LEAD_TIME = "lastAlarmLeadTime"
    }

    private lateinit var tripActionsViewModel: TripActionsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showsDialog = true
        tripActionsViewModel = ViewModelProvider(requireActivity())[TripActionsViewModel::class.java]
    }

    override fun onStart() {
        super.onStart()
        setUpNumberPickerValues(requireDialog())
    }

    private fun setUpNumberPickerValues(dialog: Dialog) {
        val preferences = requireActivity().getPreferences(Context.MODE_PRIVATE)
        val lastAlarmLeadTime = preferences.getInt(KEY_LAST_ALARM_LEAD_TIME, 5)
        val numberPicker = dialog.findViewById<NumberPicker>(R.id.numberPicker)
        val boardedDeparture = requireNotNull(tripActionsViewModel.getFollowedDeparture())
        val timeSource = (requireActivity().application as BartRunnerApplication).timeSource
        val maxValue = boardedDeparture.getMeanSecondsLeft(timeSource) / 60

        numberPicker.minValue = 1
        numberPicker.maxValue = maxValue
        numberPicker.value = when {
            tripActionsViewModel.isAlarmPending() -> tripActionsViewModel.getAlarmLeadTimeMinutes()
            maxValue >= lastAlarmLeadTime -> lastAlarmLeadTime
            maxValue >= 5 -> 5
            maxValue >= 3 -> 3
            else -> 1
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val dialogView = activity.layoutInflater.inflate(R.layout.train_alarm_dialog, null)
        return AlertDialog.Builder(activity)
            .setTitle(R.string.set_up_departure_alarm)
            .setCancelable(true)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ ->
                val alarmLeadTime = requireDialog()
                    .findViewById<NumberPicker>(R.id.numberPicker).value
                activity.getPreferences(Context.MODE_PRIVATE).edit()
                    .putInt(KEY_LAST_ALARM_LEAD_TIME, alarmLeadTime)
                    .apply()
                tripActionsViewModel.setAlarm(alarmLeadTime)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }
}
