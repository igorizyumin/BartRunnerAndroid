package com.dougkeen.bart.data;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Checkable;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.ViewSwitcher.ViewFactory;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dougkeen.bart.R;
import com.dougkeen.bart.controls.CountdownTextView;
import com.dougkeen.bart.controls.TimedTextSwitcher;
import com.dougkeen.bart.model.Departure;

import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter for live departures in both portrait and landscape layouts. */
public class DepartureArrayAdapter
        extends RecyclerView.Adapter<DepartureArrayAdapter.ViewHolder> {

    public interface Listener {
        void onDepartureClicked(Departure departure);

        void onDepartureLongClicked(Departure departure);
    }

    private final Context context;
    private final Listener listener;
    private final List<Departure> departures = new ArrayList<>();

    public DepartureArrayAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public Departure getItem(int position) {
        return departures.get(position);
    }

    public int getCount() {
        return departures.size();
    }

    public void add(Departure departure) {
        departures.add(departure);
        notifyItemInserted(departures.size() - 1);
    }

    public void remove(Departure departure) {
        int index = departures.indexOf(departure);
        if (index >= 0) {
            departures.remove(index);
            notifyItemRemoved(index);
        }
    }

    public void clear() {
        int oldSize = departures.size();
        departures.clear();
        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.departure_listing, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    @Override
    public int getItemCount() {
        return departures.size();
    }

    public final class ViewHolder extends RecyclerView.ViewHolder {
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onDepartureClicked(getItem(position));
                }
            });
            itemView.setOnLongClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onDepartureLongClicked(getItem(position));
                    return true;
                }
                return false;
            });
        }

        void bind(final Departure departure) {
            ((Checkable) itemView).setChecked(departure.isSelected());

            TextView destination = itemView.findViewById(R.id.destinationText);
            destination.setText(departure.getTrainDestination().toString());
            int paintFlags = destination.getPaintFlags();
            destination.setPaintFlags(departure.isCanceled()
                    ? paintFlags | Paint.STRIKE_THRU_TEXT_FLAG
                    : paintFlags & ~Paint.STRIKE_THRU_TEXT_FLAG);

            String arrivesPrefix = context.getString(R.string.arrives_at_destination);
            String estimatedArrival = departure.getEstimatedArrivalTimeText(context, false);
            String transferDetails = departure.getTransferDetailsText(context);

            TextView estimatedArrivalView = itemView.findViewById(R.id.estimatedArrival);
            if (estimatedArrivalView != null) {
                ((TextView) itemView.findViewById(R.id.trainLengthText))
                        .setText(departure.getTrainLengthAndPlatform());
                if (departure.isCanceled()) {
                    estimatedArrivalView.setText("");
                } else if (!isBlank(transferDetails)) {
                    estimatedArrivalView.setText(transferDetails);
                } else if (!isBlank(estimatedArrival)) {
                    estimatedArrivalView.setText(arrivesPrefix + estimatedArrival);
                } else {
                    estimatedArrivalView.setText("");
                }
            } else {
                TimedTextSwitcher trainInfo = itemView.findViewById(R.id.trainLengthText);
                initTextSwitcher(trainInfo, R.layout.train_length_arrival_textview);
                if (!isBlank(transferDetails)) {
                    trainInfo.setCurrentText(transferDetails);
                } else if (!isBlank(estimatedArrival)) {
                    trainInfo.setCurrentText(arrivesPrefix + estimatedArrival);
                } else {
                    trainInfo.setCurrentText(departure.getTrainLengthAndPlatform());
                }
                trainInfo.setTextProvider(tick -> {
                    if (tick % 4 == 0) {
                        return departure.getTrainLengthAndPlatform();
                    }
                    if (!isBlank(transferDetails)) {
                        return transferDetails;
                    }
                    String arrival = departure.getEstimatedArrivalTimeText(context, false);
                    return isBlank(arrival) ? "" : arrivesPrefix + arrival;
                });
            }

            itemView.findViewById(R.id.destinationColorBar)
                    .setBackgroundColor(departure.getTrainDestinationColor());
            CountdownTextView countdown = itemView.findViewById(R.id.countdown);
            countdown.setText(departure.getCountdownText());
            countdown.setTextProvider(tick -> departure.getCountdownText());

            TextView departureTime = itemView.findViewById(R.id.departureTime);
            if (departureTime != null) {
                ((TextView) itemView.findViewById(R.id.uncertainty))
                        .setText(departure.getUncertaintyText());
                departureTime.setText(departure.isCanceled() ? ""
                        : "Dep " + departure.getEstimatedDepartureTimeText(context, true));
            } else {
                TimedTextSwitcher uncertainty = itemView.findViewById(R.id.uncertainty);
                initTextSwitcher(uncertainty, R.layout.uncertainty_textview);
                uncertainty.setTextProvider(tick -> tick % 4 == 0
                        ? departure.getUncertaintyText()
                        : departure.getEstimatedDepartureTimeText(context, false));
            }

            itemView.findViewById(R.id.xferIcon).setVisibility(
                    departure.getRequiresTransfer() ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void initTextSwitcher(TextSwitcher textSwitcher, final int layout) {
        if (textSwitcher.getInAnimation() == null) {
            textSwitcher.setFactory((ViewFactory) () -> LayoutInflater.from(context)
                    .inflate(layout, null));
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
