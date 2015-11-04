package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.scenario.InactiveTripsFilter;
import com.conveyal.r5.analyst.scenario.Scenario;
import gnu.trove.map.TIntIntMap;
import com.conveyal.r5.analyst.cluster.AnalystClusterRequest;
import com.conveyal.r5.analyst.cluster.ResultEnvelope;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is an exact copy of RepeatedRaptorProfileRouter that's being modified to work with (new) TransitNetworks
 * instead of (old) Graphs. We can afford the maintainability nightmare of duplicating so much code because this is
 * intended to completely replace the old class sooner than later.
 *
 * We don't need to wait for point-to-point routing and detailed walking directions etc. to be available on the new
 * TransitNetwork code to do analysis work with it.
 *
 * Perform one-to-many profile routing using repeated RAPTOR searches. In this context, profile routing means finding
 * the optimal itinerary for each departure moment in a given window, without trying to reconstruct the exact paths.
 *
 * In other contexts (Modeify-style point to point routing) we would want to include suboptimal but resonable paths
 * and retain enough information to reconstruct all those paths accounting for common trunk frequencies and stop clusters.
 *
 * This method is conceptually very similar to the work of the Minnesota Accessibility Observatory
 * (http://www.its.umn.edu/Publications/ResearchReports/pdfdownloadl.pl?id=2504)
 * They run repeated searches for each departure time in the window. We take advantage of the fact that the street
 * network is static (for the most part, assuming time-dependent turn restrictions and traffic are consistent across
 * the time window) and only run a fast transit search for each minute in the window.
 */
public class RepeatedRaptorProfileRouter {

    private static final Logger LOG = LoggerFactory.getLogger(RepeatedRaptorProfileRouter.class);

    public AnalystClusterRequest clusterRequest;

    public ProfileRequest request;

    public TransportNetwork network;

    /** Samples to propagate times to */
    private LinkedPointSet targets;

    private PropagatedTimesStore propagatedTimesStore;

    // Set this field to an existing taskStatistics before routing if you want to collect performance information.
    public TaskStatistics ts = new TaskStatistics();

    /**
     * Make a router to use for making ResultEnvelopes. This propagates travel times all the way to the target temporary
     * street vertices, so that average and maximum travel time are correct.
     *
     * Temp vertices are linked to two vertices at the ends of an edge, and it is possible that the average for the sample
     * (as well as the max) is lower than the average or the max at either of the vertices, because it may be that
     * every time the max at one vertex is occurring, a lower value is occurring at the other. This initially
     * seems improbable, but consider the case when there are two parallel transit services running out of phase.
     * It may be that some of the time it makes sense to go out of your house and turn left, and sometimes it makes
     * sense to turn right, depending on which is coming first.
     */
    public RepeatedRaptorProfileRouter(TransportNetwork network, AnalystClusterRequest clusterRequest,
                                       LinkedPointSet targets, TaskStatistics ts) {
        if (network.streetLayer != targets.streetLayer) {
            LOG.error("Transit network and target point set are not linked to the same street layer.");
        }
        this.network = network;
        this.clusterRequest = clusterRequest;
        this.targets = targets;
        this.request = clusterRequest.profileRequest;
        this.ts = ts;
    }

    public ResultEnvelope route () {

        long computationStartTime = System.currentTimeMillis();
        LOG.info("Beginning repeated RAPTOR profile request.");

        boolean isochrone = targets.pointSet instanceof WebMercatorGridPointSet;
        boolean transit = (request.transitModes != null && request.transitModes.contains("TRANSIT")); // Does the search involve transit at all?

        // Check that caller has supplied a LinkedPointSet and RaptorWorkerData when needed.
        // These are supplied by the caller because the caller maintains caches, and this router object is throw-away.
        if (targets == null) {
            throw new IllegalArgumentException("Caller must supply a LinkedPointSet.");
        }

        // WHAT WE WANT TO DO HERE:
        // - Make or get a LinkedPointSet (a regular grid if no FreeFormPointSet is supplied).
        // - Use a streetRouter to explore a circular area around the origin point.
        // - Get the travel times to transit stops from the StreetRouter (A).
        // - Get the travel time to all targets in the FreeFormPointSet using the StreetRouter's internal tree.
        // - Make RaptorWorkerData from the TransitLayer.
        // - Use a RepeatedRaptorProfileRouter, intialized with item A, to find travel times to all reachable transit stops.
        //   - The RepeatedRaptorProfileRouter propagates times out to the targets in the FreeFormPointSet as it runs.
        // - Fetch the propagated results (which will eventually be called a PointSetTimeRange)
        // - Make a result envelope from them.

        // Get travel times to street vertices near the origin, and to initial stops if we're using transit.
        long initialStopStartTime = System.currentTimeMillis();
        StreetRouter streetRouter = new StreetRouter(network.streetLayer);
        // TODO add time and distance limits to routing, not just weight.
        // TODO apply walk and bike speeds and maxBike time.
        streetRouter.distanceLimitMeters = transit ? 2000 : 100_000; // FIXME arbitrary, and account for bike or car access mode
        streetRouter.setOrigin(request.fromLat, request.fromLon);
        streetRouter.route();
        ts.initialStopSearch = (int) (System.currentTimeMillis() - initialStopStartTime);

        // Find the travel time to every target without using any transit, based on the results in the StreetRouter.
        long walkSearchStart = System.currentTimeMillis();
        PointSetTimes nonTransitTimes = targets.eval(streetRouter::getTravelTimeToVertex);
        // According to the Javadoc we do in fact want to record elapsed time for a single eval call.
        ts.walkSearch = (int) (System.currentTimeMillis() - walkSearchStart);

        if (transit) {
            RaptorWorker worker = new RaptorWorker(network.transitLayer, targets, request);
            TIntIntMap transitStopAccessTimes = streetRouter.getReachedStops();
            ts.initialStopCount = transitStopAccessTimes.size();
            LOG.info("Found {} transit stops near origin", ts.initialStopCount);

            propagatedTimesStore = worker.runRaptor(transitStopAccessTimes, nonTransitTimes, ts);
        } else {
            // Nontransit case: skip transit routing and make a propagated times store based on only one row.
            // TODO skip the transit search inside the worker and avoid this conditional.
            propagatedTimesStore = new PropagatedTimesStore(nonTransitTimes.size());
            int[][] singleRoundResults = new int[][] {nonTransitTimes.travelTimes};
            propagatedTimesStore.setFromArray(singleRoundResults, PropagatedTimesStore.ConfidenceCalculationMethod.MIN_MAX);
        }
        ts.targetsReached = propagatedTimesStore.countTargetsReached();
        ts.compute = (int) (System.currentTimeMillis() - computationStartTime);
        LOG.info("Profile request finished in {} seconds", (ts.compute) / 1000.0);

        // Turn the results of the search into isochrone geometries or accessibility data as requested.
        long resultSetStart = System.currentTimeMillis();
        ResultEnvelope envelope;

        // A destination point set was provided. We've found access times to a set of specified points.
        // TODO actually use those boolean params to calculate isochrones on a regular grid pointset
        envelope = propagatedTimesStore.makeResults(targets.pointSet, clusterRequest.includeTimes, !isochrone, isochrone);

        ts.resultSets = (int) (System.currentTimeMillis() - resultSetStart);
        return envelope;
    }
}