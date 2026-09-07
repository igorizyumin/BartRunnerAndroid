package com.dougkeen.bart.data;

import android.content.Context;
import android.graphics.Color;
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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.dougkeen.bart.R;
import com.dougkeen.bart.controls.CountdownTextView;
import com.dougkeen.bart.controls.TimedTextSwitcher;
import com.dougkeen.bart.model.Departure;
import com.dougkeen.bart.model.TimeSource;
import com.dougkeen.bart.model.TripLeg;
import com.dougkeen.bart.model.TripStop;
import com.dougkeen.bart.presentation.DepartureTextFormatter;

import java.util.List;
import java.util.Objects;

/** RecyclerView adapter for live departures in both portrait and landscape layouts. */
public class DepartureArrayAdapter
        extends ListAdapter<Departure, DepartureArrayAdapter.ViewHolder> {

    private static final DiffUtil.ItemCallback<Departure> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Departure>() {
                @Override
                public boolean areItemsTheSame(@NonNull Departure oldItem,
                                               @NonNull Departure newItem) {
                    return oldItem.getIdentity().equals(newItem.getIdentity());
                }

                @Override
                public boolean areContentsTheSame(@NonNull Departure oldItem,
                                                  @NonNull Departure newItem) {
                    return contentsSame(oldItem, newItem);
                }
            };

    public interface Listener {
        void onDepartureClicked(Departure departure);

        void onDepartureLongClicked(Departure departure);
    }

    private final Context context;
    private final Listener listener;
    private final TimeSource timeSource;
    private String selectedDepartureIdentity;
    private long tick;

    public DepartureArrayAdapter(Context context, Listener listener,
                                 TimeSource timeSource) {
        super(DIFF_CALLBACK);
        this.context = context;
        this.listener = listener;
        this.timeSource = timeSource;
    }

    public void setTick(long tick) {
        this.tick = tick;
        notifyDataSetChanged();
    }

    public Departure itemAt(int position) {
        return getItem(position);
    }

    /** Presentation-only selection state; it is not stored on transit values. */
    public void setSelectedDepartureIdentity(String identity) {
        selectedDepartureIdentity = identity;
        notifyDataSetChanged();
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
            ((Checkable) itemView).setChecked(selectedDepartureIdentity != null
                    && selectedDepartureIdentity.equals(departure.getIdentity()));

            TextView destination = itemView.findViewById(R.id.destinationText);
            destination.setText(departure.getTrainDestination().toString());
            int paintFlags = destination.getPaintFlags();
            destination.setPaintFlags(departure.isCanceled()
                    ? paintFlags | Paint.STRIKE_THRU_TEXT_FLAG
                    : paintFlags & ~Paint.STRIKE_THRU_TEXT_FLAG);

            String arrivesPrefix = context.getString(R.string.arrives_at_destination);
            long nowMillis = timeSource.nowMillis();
            String estimatedArrival = DepartureTextFormatter.estimatedArrivalTime(
                    context, departure, false);
            String transferDetails = DepartureTextFormatter.transferDetails(
                    context, departure);

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
                trainInfo.setCurrentText(tick % 4 == 0
                        ? departure.getTrainLengthAndPlatform()
                        : !isBlank(transferDetails) ? transferDetails
                        : isBlank(estimatedArrival) ? "" : arrivesPrefix + estimatedArrival);
            }

            int destinationColor;
            try {
                destinationColor = Color.parseColor(departure.getTrainDestinationColorHex());
            } catch (IllegalArgumentException exception) {
                destinationColor = Color.WHITE;
            }
            itemView.findViewById(R.id.destinationColorBar)
                    .setBackgroundColor(destinationColor);
            CountdownTextView countdown = itemView.findViewById(R.id.countdown);
            countdown.setText(DepartureTextFormatter.countdown(
                    context, departure, nowMillis));

            TextView departureTime = itemView.findViewById(R.id.departureTime);
            if (departureTime != null) {
                ((TextView) itemView.findViewById(R.id.uncertainty))
                        .setText(departure.getUncertaintyText(timeSource));
                departureTime.setText(departure.isCanceled() ? ""
                        : "Dep " + DepartureTextFormatter.estimatedDepartureTime(
                                context, departure, true));
            } else {
                TimedTextSwitcher uncertainty = itemView.findViewById(R.id.uncertainty);
                initTextSwitcher(uncertainty, R.layout.uncertainty_textview);
                uncertainty.setCurrentText(tick % 4 == 0
                        ? departure.getUncertaintyText(timeSource)
                        : DepartureTextFormatter.estimatedDepartureTime(
                                context, departure, false));
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

    private static boolean contentsSame(Departure oldItem, Departure newItem) {
        return Objects.equals(oldItem.getOrigin(), newItem.getOrigin())
                && Objects.equals(oldItem.getTrainDestination(),
                newItem.getTrainDestination())
                && Objects.equals(oldItem.getPassengerDestination(),
                newItem.getPassengerDestination())
                && Objects.equals(oldItem.getLine(), newItem.getLine())
                && Objects.equals(oldItem.getTrainDestinationColorHex(),
                newItem.getTrainDestinationColorHex())
                && Objects.equals(oldItem.getTrainDestinationColorText(),
                newItem.getTrainDestinationColorText())
                && Objects.equals(oldItem.getPlatform(), newItem.getPlatform())
                && Objects.equals(oldItem.getDirection(), newItem.getDirection())
                && oldItem.isBikeAllowed() == newItem.isBikeAllowed()
                && Objects.equals(oldItem.getTrainLength(), newItem.getTrainLength())
                && oldItem.getRequiresTransfer() == newItem.getRequiresTransfer()
                && oldItem.isTransferScheduled() == newItem.isTransferScheduled()
                && oldItem.isLimited() == newItem.isLimited()
                && oldItem.isCanceled() == newItem.isCanceled()
                && oldItem.getMinutes() == newItem.getMinutes()
                && oldItem.getMinEstimate() == newItem.getMinEstimate()
                && oldItem.getMaxEstimate() == newItem.getMaxEstimate()
                && oldItem.getEstimatedTripTime() == newItem.getEstimatedTripTime()
                && oldItem.getArrivalTimeOverride() == newItem.getArrivalTimeOverride()
                && oldItem.isListedInETDs() == newItem.isListedInETDs()
                && legsSame(oldItem, newItem);
    }

    private static boolean legsSame(Departure oldItem, Departure newItem) {
        List<TripLeg> oldLegs = oldItem.getTripLegs();
        List<TripLeg> newLegs = newItem.getTripLegs();
        if (oldLegs.size() != newLegs.size()) {
            return false;
        }
        for (int index = 0; index < oldLegs.size(); index++) {
            TripLeg oldLeg = oldLegs.get(index);
            TripLeg newLeg = newLegs.get(index);
            if (!Objects.equals(oldLeg.getLine(), newLeg.getLine())
                    || !Objects.equals(oldLeg.getOrigin(), newLeg.getOrigin())
                    || !Objects.equals(oldLeg.getDestination(), newLeg.getDestination())
                    || !Objects.equals(oldLeg.getTrainDestination(),
                    newLeg.getTrainDestination())
                    || !Objects.equals(oldLeg.getTripId(), newLeg.getTripId())
                    || oldLeg.getDepartureTime() != newLeg.getDepartureTime()
                    || oldLeg.getArrivalTime() != newLeg.getArrivalTime()
                    || oldLeg.getMinimumTransferSecondsAfter()
                    != newLeg.getMinimumTransferSecondsAfter()
                    || !stopsSame(oldLeg.getStops(), newLeg.getStops())) {
                return false;
            }
        }
        return true;
    }

    private static boolean stopsSame(List<TripStop> oldStops, List<TripStop> newStops) {
        if (oldStops.size() != newStops.size()) {
            return false;
        }
        for (int index = 0; index < oldStops.size(); index++) {
            TripStop oldStop = oldStops.get(index);
            TripStop newStop = newStops.get(index);
            if (!Objects.equals(oldStop.getStation(), newStop.getStation())
                    || oldStop.getArrivalTime() != newStop.getArrivalTime()
                    || oldStop.getDepartureTime() != newStop.getDepartureTime()) {
                return false;
            }
        }
        return true;
    }
}
