package com.dougkeen.bart.backend;

import com.dougkeen.bart.model.Alert;
import com.dougkeen.bart.networktasks.GtfsRealtimeFeedIndex;
import com.google.transit.realtime.GtfsRealtime;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/** Converts the latest alert feed into the app's alert model. */
public final class AlertProjection implements TransitProjection<Alert.AlertList> {
    @Override
    public Alert.AlertList project(TransitFeedSnapshot snapshot) {
        Alert.AlertList result = new Alert.AlertList();
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT,
                DateFormat.SHORT, Locale.getDefault());
        GtfsRealtimeFeedIndex index = snapshot.getAlertIndex();
        for (GtfsRealtime.FeedEntity entity : index.getAlertEntities()) {
            if (!entity.hasAlert()) {
                continue;
            }
            GtfsRealtime.Alert source = entity.getAlert();
            Alert alert = new Alert(entity.getId());
            alert.setType(source.hasEffect() ? source.getEffect().name() : "");
            alert.setDescription(text(source.hasHeaderText()
                    ? source.getHeaderText() : null, source.hasDescriptionText()
                    ? source.getDescriptionText() : null));
            if (!source.getActivePeriodList().isEmpty()) {
                GtfsRealtime.TimeRange period = source.getActivePeriod(0);
                if (period.hasStart()) {
                    alert.setPostedTime(format.format(new Date(
                            period.getStart() * 1000L)));
                }
                if (period.hasEnd()) {
                    alert.setExpiresTime(format.format(new Date(
                            period.getEnd() * 1000L)));
                }
            }
            result.addAlert(alert);
        }
        if (!result.hasAlerts()) {
            result.setNoDelaysReported(true);
        }
        return result;
    }

    @Override
    public boolean areEquivalent(Alert.AlertList previous,
                                 Alert.AlertList current) {
        if (previous == current) {
            return true;
        }
        if (previous == null || current == null
                || previous.areNoDelaysReported()
                != current.areNoDelaysReported()
                || previous.getAlerts().size() != current.getAlerts().size()) {
            return false;
        }
        for (int i = 0; i < previous.getAlerts().size(); i++) {
            Alert left = previous.getAlerts().get(i);
            Alert right = current.getAlerts().get(i);
            if (!Objects.equals(left.getId(), right.getId())
                    || !Objects.equals(left.getType(), right.getType())
                    || !Objects.equals(left.getDescription(), right.getDescription())
                    || !Objects.equals(left.getPostedTime(), right.getPostedTime())
                    || !Objects.equals(left.getExpiresTime(), right.getExpiresTime())) {
                return false;
            }
        }
        return true;
    }

    private static String text(GtfsRealtime.TranslatedString header,
                               GtfsRealtime.TranslatedString description) {
        String headerText = translation(header);
        String descriptionText = translation(description);
        if (headerText.isEmpty()) {
            return descriptionText;
        }
        if (descriptionText.isEmpty()) {
            return headerText;
        }
        return headerText + "\n" + descriptionText;
    }

    private static String translation(GtfsRealtime.TranslatedString value) {
        if (value == null || value.getTranslationList().isEmpty()) {
            return "";
        }
        return value.getTranslation(0).getText();
    }
}
