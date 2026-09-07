package com.dougkeen.bart.data;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.ViewSwitcher.ViewFactory;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.dougkeen.bart.R;
import com.dougkeen.bart.controls.CountdownTextView;
import com.dougkeen.bart.controls.TimedTextSwitcher;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.presentation.DepartureTextFormatter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** RecyclerView adapter for favorite routes and their live departure summary. */
public class FavoritesArrayAdapter
        extends ListAdapter<StationPair, FavoritesArrayAdapter.ViewHolder> {

    private static final DiffUtil.ItemCallback<StationPair> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<StationPair>() {
                @Override
                public boolean areItemsTheSame(@NonNull StationPair oldItem,
                                               @NonNull StationPair newItem) {
                    return oldItem.equals(newItem);
                }

                @Override
                public boolean areContentsTheSame(@NonNull StationPair oldItem,
                                                  @NonNull StationPair newItem) {
                    return oldItem.fareEquals(newItem)
                            && oldItem.getAverageTripLength()
                            == newItem.getAverageTripLength()
                            && oldItem.getAverageTripSampleCount()
                            == newItem.getAverageTripSampleCount();
                }
            };

    public interface Listener {
        void onFavoriteClicked(StationPair pair);

        void onFavoriteLongClicked(StationPair pair);
    }

    private final Context context;
    private final Listener listener;
    private final TimeSource timeSource;
    private Map<StationPair, Departure> firstDepartures = Collections.emptyMap();
    private long tick;

    public FavoritesArrayAdapter(Context context, List<StationPair> items,
                                 Listener listener, TimeSource timeSource) {
        super(DIFF_CALLBACK);
        this.context = context;
        this.listener = listener;
        this.timeSource = timeSource;
        submitList(items);
    }

    public void setFirstDepartures(Map<StationPair, Departure> firstDepartures) {
        this.firstDepartures = Collections.unmodifiableMap(
                new java.util.HashMap<>(firstDepartures));
        notifyDataSetChanged();
    }

    public void setTick(long tick) {
        this.tick = tick;
        notifyDataSetChanged();
    }

    public StationPair itemAt(int position) {
        return getItem(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.favorite_listing, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position), tick);
    }

    public final class ViewHolder extends RecyclerView.ViewHolder {
        private final TimedTextSwitcher uncertainty;
        private final CountdownTextView countdown;
        private final TextView origin;
        private final TextView destination;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            uncertainty = itemView.findViewById(R.id.uncertainty);
            countdown = itemView.findViewById(R.id.countdownText);
            origin = itemView.findViewById(R.id.originText);
            destination = itemView.findViewById(R.id.destinationText);
            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onFavoriteClicked(getItem(position));
                }
            });
            itemView.setOnLongClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onFavoriteLongClicked(getItem(position));
                    return true;
                }
                return false;
            });
        }

        void bind(StationPair pair, long tick) {
            origin.setText(pair.getOrigin().getName());
            View to = itemView.findViewById(R.id.to);
            if (pair.getDestination() == null) {
                to.setVisibility(View.GONE);
                destination.setVisibility(View.GONE);
            } else {
                to.setVisibility(View.VISIBLE);
                destination.setVisibility(View.VISIBLE);
                destination.setText(pair.getDestination().getName());
            }
            initTextSwitcher(uncertainty);

            Departure firstDeparture = firstDepartures.get(pair);
            if (firstDeparture == null) {
                countdown.setText("");
                uncertainty.setCurrentText(pair.getFare());
                return;
            }

            countdown.setText(DepartureTextFormatter.countdown(
                    context, firstDeparture, timeSource));

            String uncertaintyText = DepartureTextFormatter.uncertainty(
                    context, firstDeparture, timeSource);
            uncertainty.setCurrentText(favoriteSecondaryText(
                    pair, firstDeparture, tick, uncertaintyText));
        }
    }

    private String favoriteSecondaryText(StationPair pair, Departure departure,
                                         long tick, String uncertainty) {
        String arrival = DepartureTextFormatter.estimatedArrivalTime(
                context, departure, true);
        int mod = isBlank(arrival) ? 6 : 8;
        if (tick % mod <= 1) {
            return pair.getFare();
        } else if (tick % mod <= 3) {
            return context.getString(R.string.departure_short,
                    DepartureTextFormatter.estimatedDepartureTime(
                            context, departure, true));
        } else if (mod == 8 && tick % mod <= 5) {
                    return context.getString(R.string.arrival_short, arrival);
        }
        return isBlank(uncertainty) ? pair.getFare() : uncertainty;
    }

    private void initTextSwitcher(TextSwitcher textSwitcher) {
        if (textSwitcher.getInAnimation() == null) {
            textSwitcher.setFactory((ViewFactory) () -> LayoutInflater.from(context)
                    .inflate(R.layout.uncertainty_textview, null));
            textSwitcher.setInAnimation(AnimationUtils.loadAnimation(
                    context, android.R.anim.slide_in_left));
            textSwitcher.setOutAnimation(AnimationUtils.loadAnimation(
                    context, android.R.anim.slide_out_right));
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

}
