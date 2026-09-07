package com.dougkeen.bart.data;

import android.app.Activity;
import android.content.Context;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.ViewSwitcher.ViewFactory;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.R;
import com.dougkeen.bart.backend.RouteDepartureProjection;
import com.dougkeen.bart.backend.TransitProjectionListener;
import com.dougkeen.bart.backend.TransitRepository;
import com.dougkeen.bart.controls.CountdownTextView;
import com.dougkeen.bart.controls.TimedTextSwitcher;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.RealTimeDepartures;
import com.dougkeen.bart.model.StationPair;
import com.dougkeen.bart.presentation.DepartureTextFormatter;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** RecyclerView adapter for favorite routes and their live departure summary. */
public class FavoritesArrayAdapter
        extends RecyclerView.Adapter<FavoritesArrayAdapter.ViewHolder> {

    public interface Listener {
        void onFavoriteClicked(StationPair pair);

        void onFavoriteLongClicked(StationPair pair);
    }

    private final Activity hostActivity;
    private List<StationPair> items;
    private final Listener listener;
    private final Map<StationPair, EtdListener> etdListeners = new HashMap<>();
    private final TransitRepository transitRepository;

    public FavoritesArrayAdapter(Activity hostActivity, List<StationPair> items,
                                 Listener listener) {
        this.hostActivity = hostActivity;
        this.items = items;
        this.listener = listener;
        transitRepository = ((BartRunnerApplication) hostActivity.getApplication())
                .getTransitRepository();
        setUpEtdListeners();
    }

    public void submitList(List<StationPair> newItems) {
        clearEtdListeners();
        items = new ArrayList<>(newItems);
        setUpEtdListeners();
        notifyDataSetChanged();
    }

    public void setUpEtdListeners() {
        clearEtdListeners();
        for (StationPair item : items) {
            etdListeners.put(item, new EtdListener(item));
        }
    }

    public void clearEtdListeners() {
        for (EtdListener listener : etdListeners.values()) {
            listener.close();
        }
        etdListeners.clear();
    }

    public boolean areEtdListenersActive() {
        return !etdListeners.isEmpty();
    }

    public void close() {
        clearEtdListeners();
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
        if (transitRepository != null) {
            etdListeners.put(item, new EtdListener(item));
        }
        notifyItemInserted(items.size() - 1);
    }

    public void remove(StationPair item) {
        int index = items.indexOf(item);
        if (index < 0) {
            return;
        }
        EtdListener etdListener = etdListeners.remove(item);
        if (etdListener != null) {
            etdListener.close();
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
        if (transitRepository != null) {
            etdListeners.put(item, new EtdListener(item));
        }
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
            origin.setText(pair.getOrigin().name);
            View to = itemView.findViewById(R.id.to);
            if (pair.getDestination() == null) {
                to.setVisibility(View.GONE);
                destination.setVisibility(View.GONE);
            } else {
                to.setVisibility(View.VISIBLE);
                destination.setVisibility(View.VISIBLE);
                destination.setText(pair.getDestination().name);
            }
            initTextSwitcher(uncertainty);

            EtdListener etdListener = etdListeners.get(pair);
            Departure firstDeparture = etdListener == null
                    ? null : etdListener.getFirstDeparture();
            if (firstDeparture == null) {
                countdown.setText("");
                uncertainty.setCurrentText(pair.getFare());
                countdown.setTextProvider(null);
                uncertainty.setTextProvider(null);
                return;
            }

            countdown.setText(DepartureTextFormatter.countdown(hostActivity, firstDeparture));
            countdown.setTextProvider(tick -> {
                Departure departure = etdListener.getFirstDeparture();
                return departure == null ? "" : DepartureTextFormatter.countdown(
                        hostActivity, departure);
            });

            String uncertaintyText = firstDeparture.getUncertaintyText();
            uncertainty.setCurrentText(isBlank(uncertaintyText)
                    ? pair.getFare() : uncertaintyText);
            uncertainty.setTextProvider(tick -> {
                Departure departure = etdListener.getFirstDeparture();
                if (departure == null) {
                    return pair.getFare();
                }
                String arrival = DepartureTextFormatter.estimatedArrivalTime(
                        hostActivity, departure, true);
                int mod = isBlank(arrival) ? 6 : 8;
                if (tick % mod <= 1) {
                    return pair.getFare();
                } else if (tick % mod <= 3) {
                    return "Dep " + DepartureTextFormatter.estimatedDepartureTime(
                            hostActivity, departure, true);
                } else if (mod == 8 && tick % mod <= 5) {
                    return "Arr " + arrival;
                }
                return departure.getUncertaintyText();
            });
        }
    }

    private void initTextSwitcher(TextSwitcher textSwitcher) {
        if (textSwitcher.getInAnimation() == null) {
            textSwitcher.setFactory((ViewFactory) () -> LayoutInflater.from(hostActivity)
                    .inflate(R.layout.uncertainty_textview, null));
            textSwitcher.setInAnimation(AnimationUtils.loadAnimation(
                    hostActivity, android.R.anim.slide_in_left));
            textSwitcher.setOutAnimation(AnimationUtils.loadAnimation(
                    hostActivity, android.R.anim.slide_out_right));
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private final class EtdListener
            implements TransitProjectionListener<RealTimeDepartures> {
        private final StationPair stationPair;
        private final TransitRepository.Subscription subscription;
        private Departure firstDeparture;

        EtdListener(StationPair stationPair) {
            this.stationPair = stationPair;
            subscription = transitRepository.subscribe(
                    new RouteDepartureProjection(stationPair), this);
        }

        void close() {
            subscription.close();
        }

        @Override
        public void onData(RealTimeDepartures result,
                           com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
            List<Departure> departures = result.getDepartures();
            for (Departure departure : departures) {
                if (!departure.hasDeparted()) {
                    if (!departure.equals(firstDeparture)) {
                        firstDeparture = departure;
                        int position = items.indexOf(stationPair);
                        if (position >= 0) {
                            notifyItemChanged(position);
                        }
                    }
                    return;
                }
            }
            firstDeparture = null;
            int position = items.indexOf(stationPair);
            if (position >= 0) {
                notifyItemChanged(position);
            }
        }

        @Override
        public void onError(Exception exception,
                            com.dougkeen.bart.backend.TransitFeedSnapshot snapshot) {
        }
        Departure getFirstDeparture() { return firstDeparture; }
    }
}
