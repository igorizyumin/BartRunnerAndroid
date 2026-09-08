package com.dougkeen.bart.data

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.presentation.DepartureTextFormatter

/** RecyclerView adapter for live departures in both portrait and landscape layouts. */
class DepartureArrayAdapter(
    private val context: Context,
    private val listener: Listener,
    private val timeSource: TimeSource,
) : ListAdapter<Departure, DepartureArrayAdapter.ViewHolder>(DIFF_CALLBACK) {
    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Departure>() {
            override fun areItemsTheSame(oldItem: Departure, newItem: Departure): Boolean =
                oldItem.identity == newItem.identity

            override fun areContentsTheSame(oldItem: Departure, newItem: Departure): Boolean =
                oldItem == newItem
        }
    }

    interface Listener {
        fun onDepartureClicked(departure: Departure)
        fun onDepartureLongClicked(departure: Departure)
    }

    private var selectedDepartureIdentity: String? = null

    fun itemAt(position: Int): Departure = getItem(position)

    /** Presentation-only selection state; it is not stored on transit values. */
    fun setSelectedDepartureIdentity(identity: String?) {
        selectedDepartureIdentity = identity
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.departure_listing, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            itemView.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let {
                    listener.onDepartureClicked(getItem(it))
                }
            }
            itemView.setOnLongClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let {
                    listener.onDepartureLongClicked(getItem(it))
                    true
                } ?: false
            }
        }

        fun bind(departure: Departure) {
            (itemView as Checkable).isChecked = selectedDepartureIdentity == departure.identity

            val destination = itemView.findViewById<TextView>(R.id.destinationText)
            destination.text = departure.trainDestination.toString()
            val paintFlags = destination.paintFlags
            destination.paintFlags = if (departure.isCanceled()) {
                paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            val arrivesPrefix = context.getString(R.string.arrives_at_destination)
            val nowMillis = timeSource.nowMillis()
            val estimatedArrival = DepartureTextFormatter.estimatedArrivalTime(context, departure, false)
            val transferDetails = DepartureTextFormatter.transferDetails(context, departure)
            itemView.findViewById<TextView>(R.id.trainLengthText).text =
                DepartureTextFormatter.trainLengthAndPlatform(context, departure)

            val estimatedArrivalView = itemView.findViewById<TextView>(R.id.estimatedArrival)
            estimatedArrivalView.text = when {
                departure.isCanceled() -> ""
                transferDetails.isNotBlank() -> transferDetails
                estimatedArrival.isNotBlank() -> arrivesPrefix + estimatedArrival
                else -> ""
            }

            val destinationColor = runCatching {
                Color.parseColor(departure.destinationColorHex ?: "")
            }.getOrDefault(Color.WHITE)
            itemView.findViewById<View>(R.id.destinationColorBar).setBackgroundColor(destinationColor)
            itemView.findViewById<TextView>(R.id.countdown).text =
                DepartureTextFormatter.countdown(context, departure, nowMillis)
            itemView.findViewById<TextView>(R.id.uncertainty).text =
                DepartureTextFormatter.uncertainty(context, departure, timeSource)
            itemView.findViewById<TextView>(R.id.departureTime).text = if (departure.isCanceled()) {
                ""
            } else {
                context.getString(
                    R.string.departure_short,
                    DepartureTextFormatter.estimatedDepartureTime(context, departure, true),
                )
            }
            itemView.findViewById<View>(R.id.xferIcon).visibility =
                if (departure.requiresTransfer) View.VISIBLE else View.INVISIBLE
        }
    }
}
