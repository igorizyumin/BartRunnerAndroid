package com.dougkeen.bart.model;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Route {
    private Station origin;
    private Station destination;
    private Line directLine;
    private Collection<Line> transferLines;
    private boolean requiresTransfer;
    private Station transferStation;
    private String direction;
    private List<Line> lines = new ArrayList<Line>();
    private List<Station> transferStations = new ArrayList<Station>();

    public Station getOrigin() {
        return origin;
    }

    public void setOrigin(Station origin) {
        this.origin = origin;
    }

    public Station getDestination() {
        return destination;
    }

    public void setDestination(Station destination) {
        this.destination = destination;
    }

    public Line getDirectLine() {
        return directLine;
    }

    public void setDirectLine(Line line) {
        this.directLine = line;
        if (lines.isEmpty() && line != null) {
            lines.add(line);
        }
    }

    public Collection<Line> getTransferLines() {
        return transferLines;
    }

    public void setTransferLines(Collection<Line> transferLines) {
        this.transferLines = transferLines;
        if (lines.size() <= 1 && transferLines != null) {
            for (Line line : transferLines) {
                if (!lines.contains(line)) {
                    lines.add(line);
                }
            }
        }
    }

    public boolean hasTransfer() {
        return requiresTransfer;
    }

    public void setTransfer(boolean requiresTransfer) {
        this.requiresTransfer = requiresTransfer;
    }

    public Station getTransferStation() {
        return transferStation;
    }

    public void setTransferStation(Station transferStation) {
        this.transferStation = transferStation;
        if (transferStation != null && transferStations.isEmpty()) {
            transferStations.add(transferStation);
        }
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public List<Line> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public void setLines(List<Line> lines) {
        this.lines = new ArrayList<Line>();
        if (lines != null) {
            this.lines.addAll(lines);
        }
        if (directLine == null && !this.lines.isEmpty()) {
            directLine = this.lines.get(0);
        }
    }

    public List<Station> getTransferStations() {
        return Collections.unmodifiableList(transferStations);
    }

    public void setTransferStations(List<Station> transferStations) {
        this.transferStations = new ArrayList<Station>();
        if (transferStations != null) {
            this.transferStations.addAll(transferStations);
        }
        transferStation = this.transferStations.isEmpty()
                ? null : this.transferStations.get(0);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Route [origin=");
        builder.append(origin);
        builder.append(", destination=");
        builder.append(destination);
        builder.append(", line=");
        builder.append(directLine);
        builder.append(", requiresTransfer=");
        builder.append(requiresTransfer);
        builder.append(", transferStation=");
        builder.append(transferStation);
        builder.append(", direction=");
        builder.append(direction);
        builder.append("]");
        return builder.toString();
    }

    public boolean trainDestinationIsApplicable(Station lineDestination,
                                                Line viaLine) {
        int originIndex = viaLine.stations.indexOf(origin);
        int routeDestinationIndex = viaLine.stations.indexOf(destination);
        int lineDestinationIndex = viaLine.stations
                .indexOf(lineDestination);

        boolean hasDirectRouteViaLine = originIndex >= 0 && routeDestinationIndex >= 0;

        if (requiresTransfer && !lines.isEmpty()) {
            if (transferStations.isEmpty() && directLine != null
                    && directLine.requiresTransfer) {
                return viaLine.equals(directLine.transferLine1)
                        || viaLine.equals(directLine.transferLine2);
            }
            // Departures are queried at the passenger origin, so only the
            // first line of a multi-leg route can be represented by an ETD
            // returned for this route.  The remaining lines are used later
            // when the connecting trips are paired with this departure.
            if (!viaLine.equals(lines.get(0))) {
                return false;
            }
            Station firstTransfer = transferStations.isEmpty()
                    ? destination : transferStations.get(0);
            int transferIndex = viaLine.stations.indexOf(firstTransfer);
            if (originIndex < 0 || transferIndex < 0 || lineDestinationIndex < 0) {
                return false;
            }
            int direction = Integer.compare(transferIndex, originIndex);
            return direction != 0
                    && Integer.compare(lineDestinationIndex, originIndex)
                    == direction
                    && Integer.compare(lineDestinationIndex, transferIndex)
                    == direction;
        } else {
            return originIndex >= 0 && routeDestinationIndex >= 0
                    && lineDestinationIndex >= 0
                    && ((originIndex <= routeDestinationIndex
                    && routeDestinationIndex <= lineDestinationIndex
                    && lineDestinationIndex >= originIndex)
                    || (originIndex >= routeDestinationIndex
                    && routeDestinationIndex >= lineDestinationIndex
                    && lineDestinationIndex <= originIndex));
        }
    }

}
