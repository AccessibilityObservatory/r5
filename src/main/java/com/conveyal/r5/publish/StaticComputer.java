package com.conveyal.r5.publish;

import com.conveyal.gtfs.validator.service.GeoUtils;
import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.profile.PathWithTimes;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.Path;
import com.conveyal.r5.profile.RaptorState;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import com.vividsolutions.jts.geom.Coordinate;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

/**
 * Compute a static site result for a single origin.
 */
public class StaticComputer implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(StaticComputer.class);
    private StaticSiteRequest.PointRequest req;
    private TransportNetwork network;
    private TaskStatistics taskStatistics;

    /** Compute the origin specified by x and y (relative to the request origin). Provide a network with the scenario pre-applied. */
    public StaticComputer (StaticSiteRequest.PointRequest req, TransportNetwork network, TaskStatistics ts) {
        this.req = req;
        this.network = network;
        this.taskStatistics = ts;
    }

    // This completes the implementation of the Runnable interface so a StaticComputer can be run in a thread.
    public void run () {
        // dump the times in the described format. They're small enough to keep in memory for now.
        try {
            OutputStream os = StaticDataStore.getOutputStream(req.request, req.x + "/" + req.y + ".dat", "application/octet-stream");
            write(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Error saving origin data", e);
        }
    }

    // This does the main calculations for a single origin point.
    // It then writes out the travel times to all grid cells near the origin (walking or biking on-street) followed by
    // the travel times to every reached stop in the network.
    // TODO rename and/or refactor to reduce side effects (pull computation out of writing logic).
    public void write (OutputStream os) throws IOException {
        WebMercatorGridPointSet points = network.gridPointSet;
        double lat = points.pixelToLat(points.north + req.y);
        double lon = points.pixelToLon(points.west + req.x);

        TaskStatistics ts = new TaskStatistics();

        // Perform street search to find transit stops and non-transit times.
        StreetRouter sr = new StreetRouter(network.streetLayer);
        sr.distanceLimitMeters = 2000;
        sr.setOrigin(lat, lon);
        sr.dominanceVariable = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        sr.route();

        // Get the travel times to all stops reached in the initial on-street search. Convert distances to speeds.
        TIntIntMap accessTimes = sr.getReachedStops();
        for (TIntIntIterator it = accessTimes.iterator(); it.hasNext();) {
            it.advance();
            it.setValue(it.value() / (int) (req.request.request.walkSpeed * 1000));
        }

        // Create a new Raptor Worker.
        // Tell it that we want a travel time to each stop by leaving the point set parameter null.
        RaptorWorker worker = new RaptorWorker(network.transitLayer, null, req.request.request);

        // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
        StaticPropagatedTimesStore pts = (StaticPropagatedTimesStore) worker.runRaptor(accessTimes, null, ts);

        long nonTransitStart = System.currentTimeMillis();

        // Get non-transit times: a pointset around the search origin.
        // FIXME we should not be using hard-coded grid sizes to allow different grid zoom levels.
        WebMercatorGridPointSet subPointSet = new WebMercatorGridPointSet(WebMercatorGridPointSet.DEFAULT_ZOOM,
                points.west + req.x - 20, points.north + req.y - 20, 41, 41);
        LinkedPointSet subLinked = new LinkedPointSet(network.linkedGridPointSet, subPointSet);
        PointSetTimes nonTransitTimes = subLinked.eval(sr::getTravelTimeToVertex);

        long outputStart = System.currentTimeMillis();
        LOG.info("Sampling non-transit times took {}s", (outputStart - nonTransitStart) / 1000.0);

        LittleEndianIntOutputStream out = new LittleEndianIntOutputStream(new BufferedOutputStream(os));

        // First write out the values for nearby pixels
        out.writeInt(20);

        int previous = 0;
        for (int time : nonTransitTimes.travelTimes) {
            if (time == Integer.MAX_VALUE) time = -1;

            out.writeInt(time - previous);
            previous = time;
        }

        int iterations = pts.times.length;
        int stops = pts.times[0].length;

        // number of stops
        out.writeInt(stops);
        // number of iterations
        out.writeInt(iterations);

        double sum = 0;

        for (int stop = 0; stop < stops; stop++) {
            int prev = 0;
            int prevPath = 0;
            int maxPathIdx = 0;
            int previousInVehicleTravelTime = 0;
            int previousWaitTime = 0;

            TObjectIntMap<Path> paths = new TObjectIntHashMap<>();
            List<Path> pathList = new ArrayList<>();

            for (int iter = 0, stateIteration = 0; iter < iterations; iter++, stateIteration++) {
                // advance past states that are not included in averages
                while (!worker.includeInAverages.get(stateIteration)) stateIteration++;

                int time = pts.times[iter][stop];
                if (time == Integer.MAX_VALUE) time = -1;
                else time /= 60;

                out.writeInt(time - prev);
                prev = time;

                if (worker.statesEachIteration != null) {
                    RaptorState state = worker.statesEachIteration.get(stateIteration);
                    int inVehicleTravelTime = state.inVehicleTravelTime[stop] / 60;
                    out.writeInt(inVehicleTravelTime - previousInVehicleTravelTime);
                    previousInVehicleTravelTime = inVehicleTravelTime;

                    int waitTime = state.waitTime[stop] / 60;
                    out.writeInt(waitTime - previousWaitTime);
                    previousWaitTime = waitTime;

                    if (inVehicleTravelTime + waitTime > time && time != -1) {
                        LOG.info("Wait and in vehicle travel time greater than total time");
                    }

                    // write out which path to use, delta coded
                    int pathIdx = -1;

                    // only compute a path if this stop was reached
                    if (state.bestNonTransferTimes[stop] != RaptorWorker.UNREACHED) {
                        // TODO reuse pathwithtimes?
                        Path path = new Path(state, stop);
                        if (!paths.containsKey(path)) {
                            paths.put(path, maxPathIdx++);
                            pathList.add(path);
                        }

                        pathIdx = paths.get(path);
                    }

                    out.writeInt(pathIdx - prevPath);
                    prevPath = pathIdx;
                } else {
                    out.writeInt(-1); // In vehicle time
                    out.writeInt(-1); // Walk time
                    out.writeInt(-1); // path index
                }
            }

            sum += pathList.size();

            // write the paths
            out.writeInt(pathList.size());
            for (Path path : pathList) {
                out.writeInt(path.patterns.length);

                for (int i = 0; i < path.patterns.length; i++) {
                    out.writeInt(path.boardStops[i]);
                    out.writeInt(path.patterns[i]);
                    out.writeInt(path.alightStops[i]);
                }
            }
        }

        LOG.info("Writing output to broker took {}s", (System.currentTimeMillis() - outputStart) / 1000.0);
        LOG.info("Average of {} paths per destination stop", sum / stops);

        out.flush();
    }

}
