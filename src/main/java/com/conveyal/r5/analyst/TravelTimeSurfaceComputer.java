package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AccessGridWriter;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceRequest;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.IntStream;

/**
 * This computes a travel time surface and returns it in access grid format, with each pixel of the grid containing
 * different percentiles of travel time requested by the frontend.
 */
public class TravelTimeSurfaceComputer {
    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeSurfaceComputer.class);
    private static final WebMercatorGridPointSetCache pointSetCache = new WebMercatorGridPointSetCache();

    public final TravelTimeSurfaceRequest request;
    public final TransportNetwork network;

    public TravelTimeSurfaceComputer (TravelTimeSurfaceRequest request, TransportNetwork network) {
        this.request = request;
        this.network = network;
    }

    public void write (OutputStream os) throws IOException {
        StreetMode accessMode = LegMode.getDominantStreetMode(request.request.accessModes);
        StreetMode directMode = LegMode.getDominantStreetMode(request.request.directModes);

        int nPercentiles = request.percentiles.length;

        WebMercatorGridPointSet destinations = pointSetCache.get(request.zoom, request.west, request.north, request.width, request.height);

        AccessGridWriter output;
        try {
            output = new AccessGridWriter(request.zoom, request.west, request.north, request.width, request.height, nPercentiles);
        } catch (IOException e) {
            // in memory, should not be able to throw this
            throw new RuntimeException(e);
        }

        if (request.request.transitModes.isEmpty()) {
            // non transit search
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.timeLimitSeconds = request.request.maxTripDurationMinutes * 60;
            sr.streetMode = directMode;
            sr.dominanceVariable = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
            sr.profileRequest = request.request;
            sr.setOrigin(request.request.fromLat, request.request.fromLon);
            sr.route();

            int offstreetTravelSpeedMillimetersPerSecond = (int) (request.request.getSpeed(directMode) * 1000);

            LinkedPointSet linkedDestinations = destinations.link(network.streetLayer, directMode);

            int[] travelTimesToTargets = linkedDestinations.eval(sr::getTravelTimeToVertex, offstreetTravelSpeedMillimetersPerSecond).travelTimes;
            for (int target = 0; target < travelTimesToTargets.length; target++) {
                int x = target % request.width;
                int y = target / request.width;

                final int travelTimeMinutes =
                        travelTimesToTargets[target] == RaptorWorker.UNREACHED ? RaptorWorker.UNREACHED : travelTimesToTargets[target] / 60;
                // the frontend expects percentiles of travel time. There is no variation in nontransit travel time so
                // just replicate the same number repeatedly. This could be improved, but at least it will compress well.
                // int divide (floor) used below as well. TODO is this wise?
                int[] results = IntStream.range(0, nPercentiles).map(i -> travelTimeMinutes).toArray();
                try {
                    output.writePixel(x, y, results);
                } catch (IOException e) {
                    // can't happen as we're not using a file system backed output
                    throw new RuntimeException(e);
                }
            }
        } else {
            // we always walk to egress from transit, but we may have a different access mode.
            // if the access mode is also walk, the pointset's linkage cache will return two references to the same
            // linkage below
            // TODO use directMode? Is that a resource limiting issue?
            // also gridcomputer uses accessMode to avoid running two street searches
            LinkedPointSet linkedDestinationsAccess = destinations.link(network.streetLayer, accessMode);
            LinkedPointSet linkedDestinationsEgress = destinations.link(network.streetLayer, StreetMode.WALK);

            if (!request.request.directModes.equals(request.request.accessModes)) {
                LOG.warn("Disparate direct modes and access modes are not supported in analysis mode.");
            }

            // Perform street search to find transit stops and non-transit times.
            StreetRouter sr = new StreetRouter(network.streetLayer);
            sr.profileRequest = request.request;

            TIntIntMap accessTimes;
            int offstreetTravelSpeedMillimetersPerSecond = (int) (request.request.getSpeed(accessMode) * 1000);
            int[] nonTransitTravelTimesToDestinations;

            if (request.request.accessModes.contains(LegMode.CAR_PARK)) {
                //Currently first search from origin to P+R is hardcoded as time dominance variable for Max car time seconds
                //Second search from P+R to stops is not actually a search we just return list of all reached stops for each found P+R.
                // If multiple P+Rs reach the same stop, only one with shortest time is returned. Stops were searched for during graph building phase.
                // time to stop is time from CAR streetrouter to stop + CAR PARK time + time to walk to stop based on request walk speed
                //by default 20 CAR PARKS are found it can be changed with sr.maxVertices variable
                sr = PointToPointQuery.findParkRidePath(request.request, sr, network.transitLayer);

                if (sr == null) {
                    // Origin not found. Return an empty access times map, as is done by the other conditions for other modes.
                    // TODO this is ugly. we should have a way to break out of the search early (here and in other methods).
                    accessTimes = new TIntIntHashMap();
                } else {
                    accessTimes = sr.getReachedStops();
                }

                // disallow non-transit access
                // TODO should we allow non transit access with park and ride?
                nonTransitTravelTimesToDestinations = new int[linkedDestinationsAccess.size()];
                Arrays.fill(nonTransitTravelTimesToDestinations, RaptorWorker.UNREACHED);
            } else if (accessMode == StreetMode.WALK) {
                // Special handling for walk search, find distance in seconds and divide to match behavior at egress
                // (in stop trees). For bike/car searches this is immaterial as the access searches are already asymmetric.
                sr.streetMode = accessMode;
                sr.distanceLimitMeters = 2000; // TODO hardwired same as gridcomputer
                sr.setOrigin(request.request.fromLat, request.request.fromLon);
                sr.dominanceVariable = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
                sr.route();

                // Get the travel times to all stops reached in the initial on-street search. Convert distances to speeds.
                // getReachedStops returns distances in this case since dominance variable is millimeters,
                // so convert to times in the loop below
                accessTimes = sr.getReachedStops();
                for (TIntIntIterator it = accessTimes.iterator(); it.hasNext(); ) {
                    it.advance();
                    it.setValue(it.value() / offstreetTravelSpeedMillimetersPerSecond);
                }

                // again, use distance / speed rather than time for symmetry with other searches
                final StreetRouter effectivelyFinalSr = sr;
                nonTransitTravelTimesToDestinations =
                        linkedDestinationsAccess.eval(v -> {
                            StreetRouter.State state = effectivelyFinalSr.getStateAtVertex(v);
                            if (state == null) return RaptorWorker.UNREACHED;
                            else return state.distance / offstreetTravelSpeedMillimetersPerSecond;
                        },
                        offstreetTravelSpeedMillimetersPerSecond).travelTimes;
            } else {
                // Other modes are already asymmetric with the egress/stop trees, so just do a time-based on street
                // search and don't worry about distance limiting.
                sr.streetMode = accessMode;
                sr.timeLimitSeconds = request.request.getMaxAccessTime(accessMode);
                sr.setOrigin(request.request.fromLat, request.request.fromLon);
                sr.dominanceVariable = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
                sr.route();
                accessTimes = sr.getReachedStops(); // already in seconds
                nonTransitTravelTimesToDestinations =
                        linkedDestinationsAccess.eval(sr::getTravelTimeToVertex, offstreetTravelSpeedMillimetersPerSecond)
                                .travelTimes;
            }

            // Create a new Raptor Worker.
            FastRaptorWorker worker = new FastRaptorWorker(network.transitLayer, request.request, accessTimes);

            // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
            int[][] transitTravelTimesToStops = worker.route();

            PerTargetPropagater perTargetPropagater = new PerTargetPropagater(transitTravelTimesToStops, nonTransitTravelTimesToDestinations, linkedDestinationsEgress, request.request, 120 * 60);
            perTargetPropagater.propagateTimes((target, times) -> {
                // sort the times at each target and read off percentiles
                Arrays.sort(times);
                int[] results = new int[nPercentiles];

                for (int i = 0; i < nPercentiles; i++) {
                    int offset = (int) Math.round(request.percentiles[i] / 100d * times.length);
                    // Int divide will floor; this is correct because value 0 has travel times of up to one minute, etc.
                    // This means that anything less than a cutoff of (say) 60 minutes (in seconds) will have value 59,
                    // which is what we want. But maybe this is tying the backend and frontend too closely.
                    results[i] = times[offset] == RaptorWorker.UNREACHED ? RaptorWorker.UNREACHED : times[offset] / 60;
                }

                int x = target % request.width;
                int y = target / request.width;
                try {
                    output.writePixel(x, y, results);
                } catch (IOException e) {
                    // can't happen as we're not using a file system backed output
                    throw new RuntimeException(e);
                }
            });
        }

        LOG.info("Travel time surface of size {}kb complete", output.getBytes().length / 1000);

        os.write(output.getBytes());

        LOG.info("Travel time surface written, appending metadata with {} warnings", network.scenarioApplicationWarnings.size());

        // Append scenario application warning JSON to result
        ResultMetadata metadata = new ResultMetadata();
        metadata.scenarioApplicationWarnings = network.scenarioApplicationWarnings;
        JsonUtilities.objectMapper.writeValue(os, metadata);

        LOG.info("Done writing");

        os.close();
    }

    private static class ResultMetadata {
        public Collection<TaskError> scenarioApplicationWarnings;
    }
}
