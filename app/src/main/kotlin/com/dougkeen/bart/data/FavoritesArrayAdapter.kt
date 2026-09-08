package com.dougkeen.bart.data

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextSwitcher
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dougkeen.bart.R
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.presentation.DepartureTextFormatter

/** RecyclerView adapter for favorite routes and their live departure summary. */
class FavoritesArrayAdapter(
    private val context: Context,
    items: List<StationPair>,
    private val listener: Listener,
    private val timeSource: TimeSource,
) : ListAdapter<StationPair, FavoritesArrayAdapter.ViewHolder>(DIFF_CALLBACK) {
    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StationPair>() {
            override fun areItemsTheSame(oldItem: StationPair, newItem: StationPair): Boolean =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: StationPair, newItem: StationPair): Boolean =
                oldItem.fareEquals(newItem) &&
                    oldItem.averageTripLength == newItem.averageTripLength &&
                    oldItem.averageTripSampleCount == newItem.averageTripSampleCount
        }
    }

    interface Listener {
        fun onFavoriteClicked(pair: StationPair)
        fun onFavoriteLongClicked(pair: StationPair)
    }

    private var firstDepartures: Map<StationPair, Departure> = emptyMap()
    private var tick: Long = 0

    init {
        submitList(items)
    }

    fun setFirstDepartures(firstDepartures: Map<StationPair, Departure>) {
        this.firstDepartures = firstDepartures.toMap()
        notifyDataSetChanged()
    }

    fun setTick(tick: Long) {
        this.tick = tick
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): StationPair = getItem(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.favorite_listing, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), tick)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val uncertainty: TextSwitcher = itemView.findViewById(R.id.uncertainty)
        private val countdown: TextView = itemView.findViewById(R.id.countdownText)
        private val origin: TextView = itemView.findViewById(R.id.originText)
        private val destination: TextView = itemView.findViewById(R.id.destinationText)

        init {
            itemView.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let {
                    listener.onFavoriteClicked(getItem(it))
                }
            }
            itemView.setOnLongClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let {
                    listener.onFavoriteLongClicked(getItem(it))
                    true
                } ?: false
            }
        }

        fun bind(pair: StationPair, tick: Long) {
            origin.text = pair.origin?.getName()
            val to = itemView.findViewById<TextView>(R.id.to)
            val destinationStation = pair.destination
            if (destinationStation == null) {
                to.visibility = View.GONE
                destination.visibility = View.GONE
            } else {
                to.visibility = View.VISIBLE
                destination.visibility = View.VISIBLE
                destination.text = destinationStation.getName()
            }
            initTextSwitcher(uncertainty)

            val firstDeparture = firstDepartures[pair]
            if (firstDeparture == null) {
                countdown.text = ""
                uncertainty.setCurrentText(pair.fare ?: "")
                return
            }

            countdown.text = DepartureTextFormatter.countdown(context, firstDeparture, timeSource)
            val uncertaintyText = DepartureTextFormatter.uncertainty(context, firstDeparture, timeSource)
            uncertainty.setCurrentText(favoriteSecondaryText(pair, firstDeparture, tick, uncertaintyText))
        }
    }

    private fun favoriteSecondaryText(
        pair: StationPair,
        departure: Departure,
        tick: Long,
        uncertainty: String,
    ): String {
        val arrival = DepartureTextFormatter.estimatedArrivalTime(context, departure, true)
        val mod = if (arrival.isBlank()) 6 else 8
        return when {
            tick % mod <= 1 -> pair.fare ?: ""
            tick % mod <= 3 -> context.getString(
                R.string.departure_short,
                DepartureTextFormatter.estimatedDepartureTime(context, departure, true),
            )
            mod == 8 && tick % mod <= 5 -> context.getString(R.string.arrival_short, arrival)
            uncertainty.isBlank() -> pair.fare ?: ""
            else -> uncertainty
        }
    }

    private fun initTextSwitcher(textSwitcher: TextSwitcher) {
        if (textSwitcher.inAnimation == null) {
            textSwitcher.setFactory {
                LayoutInflater.from(context).inflate(R.layout.uncertainty_textview, null)
            }
            textSwitcher.inAnimation = AnimationUtils.loadAnimation(context, android.R.anim.slide_in_left)
            textSwitcher.outAnimation = AnimationUtils.loadAnimation(context, android.R.anim.slide_out_right)
        }
    }
}
