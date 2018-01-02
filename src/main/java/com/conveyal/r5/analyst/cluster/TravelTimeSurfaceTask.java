package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.GridCache;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.TravelTimeSurfaceReducer;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single point, interactive task coming from the Analysis UI and returning a surface of travel
 * times to each destination (several travel times to each destination are returned, representing the percentiles
 * of travel time from the chosen origin to that destination.
 */
public class TravelTimeSurfaceTask extends AnalysisTask {
    @Override
    public Type getType() {
        return Type.TRAVEL_TIME_SURFACE;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @JsonIgnoreProperties(ignoreUnknown=true)

    /** Whether to download as a Conveyal flat binary file for display in analysis-ui, or a geotiff */
    public enum Format {
        /** Flat binary grid format */
        GRID,
        /** GeoTIFF file for download and use in GIS */
        GEOTIFF
    }

    /** Default format is a Conveyal flat binary file */
    private Format format = Format.GRID;

    public void setFormat(Format format){
        this.format = format;
    }

    public Format getFormat(){
        return format;
    }

    /**
     * Since this may be applied to many different grids, we use the extents defined in the request.
     */
    @Override
    public List<PointSet> getDestinations(TransportNetwork network, GridCache gridCache) {
        List pointSets = new ArrayList<>();
        // Use TransportNetwork gridPointSet as base to avoid relinking
        pointSets.add(gridPointSetCache.get(this.zoom, this.west, this.north, this.width, this.height, network.gridPointSet));
        return pointSets;
    }

    @Override
    public PerTargetPropagater.TravelTimeReducer getTravelTimeReducer(TransportNetwork network, OutputStream os) {
        return new TravelTimeSurfaceReducer(this, network, os);
    }
}
