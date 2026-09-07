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
import androidx.recyclerview.widget.RecyclerView;

import com.dougkeen.bart.R;
import com.dougkeen.bart.controls.CountdownTextView;
import com.dougkeen.bart.controls.TimedTextSwitcher;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.presentation.DepartureTextFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** RecyclerView adapter for favorite routes and their live departure summary. */
public class FavoritesArrayAdapter
        extends RecyclerView.Adapter<FavoritesArrayAdapter.ViewHolder> {

    public interface Listener {
        void onFavoriteClicked(StationPair pair);

        void onFavoriteLongClicked(StationPair pair);
    }

    private final Context context;
    private List<StationPair> items;
    private final Listener listener;
    private final TimeSource timeSource;
    private Map<StationPair, Departure> firstDepartures = Collections.emptyMap();

    public FavoritesArrayAdapter(Context context, List<StationPair> items,
                                 Listener listener, TimeSource timeSource) {
        this.context = context;
        this.items = items;
        this.listener = listener;
        this.timeSource = timeSource;
    }

    public void submitList(List<StationPair> newItems) {
        items = new ArrayList<>(newItems);
        notifyDataSetChanged();
    }

    public void setFirstDepartures(Map<StationPair, Departure> firstDepartures) {
        this.firstDepartures = Collections.unmodifiableMap(
                new java.util.HashMap<>(firstDepartures));
        notifyDataSetChanged();
    }

    public StationPair getItem(int position) {
        return items.get(position);
    }

    public int getCount() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public void add(StationPair item) {
        items.add(item);
        notifyItemInserted(items.size() - 1);
    }

    public void remove(StationPair item) {
        int index = items.indexOf(item);
        if (index < 0) {
            return;
        }
        items.remove(index);
        notifyItemRemoved(index);
    }

    public void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size()
                || to >= items.size()) {
            return;
        }
        StationPair item = items.remove(from);
        items.add(to, item);
        notifyItemMoved(from, to);
    }

    public void insert(StationPair item, int index) {
        int safeIndex = Math.max(0, Math.min(index, items.size()));
        items.add(safeIndex, item);
        notifyItemInserted(safeIndex);
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
        holder.bind(getItem(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
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

        void bind(StationPair pair) {
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
                countdown.setTextProvider(null);
                uncertainty.setTextProvider(null);
                return;
            }

            countdown.setText(DepartureTextFormatter.countdown(
                    context, firstDeparture, timeSource));
            countdown.setTextProvider(tick -> {
                Departure departure = firstDepartures.get(pair);
                return departure == null ? "" : DepartureTextFormatter.countdown(
                        context, departure, timeSource);
            });

            String uncertaintyText = firstDeparture.getUncertaintyText(timeSource);
            uncertainty.setCurrentText(isBlank(uncertaintyText)
                    ? pair.getFare() : uncertaintyText);
            uncertainty.setTextProvider(tick -> {
                Departure departure = firstDepartures.get(pair);
                if (departure == null) {
                    return pair.getFare();
                }
                String arrival = DepartureTextFormatter.estimatedArrivalTime(
                        context, departure, true);
                int mod = isBlank(arrival) ? 6 : 8;
                if (tick % mod <= 1) {
                    return pair.getFare();
                } else if (tick % mod <= 3) {
                    return "Dep " + DepartureTextFormatter.estimatedDepartureTime(
                            context, departure, true);
                } else if (mod == 8 && tick % mod <= 5) {
                    return "Arr " + arrival;
                }
                return departure.getUncertaintyText(timeSource);
            });
        }
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
