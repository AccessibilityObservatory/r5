package com.conveyal.r5.streets;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import gnu.trove.set.TIntSet;
import org.locationtech.jts.geom.Envelope;

import java.util.ArrayList;
import java.util.List;

public abstract class RoutableStreetLayer {
    /**
     * The radius of a circle in meters within which to search for nearby streets.
     * This should not necessarily be a constant, but even if it's made settable it should be stored in a field on this
     * class to avoid cluttering method signatures. Generally you'd set this once at startup and always use the same
     * value afterward.
     * 1.6km is really far to walk off a street. But some places have offices in the middle of big parking lots.
     */
    public static final double LINK_RADIUS_METERS = 1600;

    /**
     * Searching for streets takes a fair amount of computation, and the number of streets examined grows roughly as
     * the square of the radius. In most cases, the closest street is close to the search center point. If the specified
     * search radius exceeds this value, a mini-search will first be conducted to check for close-by streets before
     * examining every street within the full specified search radius.
     */
    public static final int INITIAL_LINK_RADIUS_METERS = 300;

    // Initialize these when we have an estimate of the number of expected edges.
    public VertexStore vertexStore = new VertexStore(100_000);
    public EdgeStore edgeStore = new EdgeStore(vertexStore, this, 200_000);

    // Edge lists should be constructed after the fact from edges. This minimizes serialized size too.
    public transient List<TIntList> outgoingEdges;
    public transient List<TIntList> incomingEdges;

    /**
     * Turn restrictions can potentially affect (include) several edges, so they are stored here and referenced
     * by index within all edges that are affected by them. TODO what if an edge is affected by multiple restrictions?
     */
    public List<TurnRestriction> turnRestrictions = new ArrayList<>();

    /**
     * The TransportNetwork containing this StreetLayer. This link up the object tree also allows us to access the
     * TransitLayer associated with this StreetLayer of the same TransportNetwork without maintaining bidirectional
     * references between the two layers.
     */
    public TransportNetwork parentNetwork = null;

    /** A spatial index of all street network edges, using fixed-point WGS84 coordinates. */
    public transient IntHashGrid spatialIndex = new IntHashGrid();

    /**
     * Spatial index of temporary edges from a scenario. We used to not have this, and we used to return all
     * temporarily added edges in every spatial index query (because spatial indexes are allowed to over-select, and
     * are filtered). However, we now create scenarios with thousands of temporary edges (from thousands of added
     * transit stops), so we keep two spatial indexes, one for the baseline network and one for the scenario additions.
     */
    protected transient IntHashGrid temporaryEdgeIndex;

    public Split findSplit(double lat, double lon, double radiusMeters, StreetMode streetMode) {
        Split split = null;
        // If the specified radius is large, first try a mini-search on the assumption
        // that most linking points are close to roads.
        if (radiusMeters > INITIAL_LINK_RADIUS_METERS) {
            split = Split.find(lat, lon, INITIAL_LINK_RADIUS_METERS, this, streetMode);
        }
        // If no split point was found by the first search (or no search was yet conducted) search with the full radius.
        if (split == null) {
            split = Split.find(lat, lon, radiusMeters, this, streetMode);
        }
        return split;
    }

    public TIntSet findEdgesInEnvelope (Envelope envelope) {
        TIntSet candidates = spatialIndex.query(envelope);
        // Include temporary edges
        if (temporaryEdgeIndex != null) {
            TIntSet temporaryCandidates = temporaryEdgeIndex.query(envelope);
            candidates.addAll(temporaryCandidates);
        }

        // Remove any edges that were temporarily deleted in a scenario.
        // This allows properly re-splitting the same edge in multiple places.
        if (edgeStore.temporarilyDeletedEdges != null) {
            candidates.removeAll(edgeStore.temporarilyDeletedEdges);
        }
        return candidates;
    }
}
