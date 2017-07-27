package com.conveyal.r5.profile;

import com.conveyal.r5.streets.LinkedPointSet;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * This class propagates from times at transit stops to times at destinations (targets). It is called with a function
 * that will be called with  the target index (which is the row-major 1D index of the destination that is being
 * propagated to) and the array of whether the target was reached within the travel time cutoff in each Monte Carlo
 * draw. This is used in GridComputer to perform bootstrapping of accessibility given median travel time. This function
 * is only called for targets that were ever reached.
 *
 * It may seem needlessly generic to use a lambda function, but it allows us to confine the bootstrapping code to GridComputer.
 * Perhaps this should be refactored to be a BootstrappingPropagater that just returns bootstrapped accessibility values.
 */
public class PerTargetPropagater {
    private static final Logger LOG = LoggerFactory.getLogger(PerTargetPropagater.class);

    /** Times at transit stops for each iteration */
    public final int[][] travelTimesToStopsEachIteration;

    /** Times at targets using the street network */
    public final int[] nonTransitTravelTimesToTargets;

    /** The travel time cutoff in this regional analysis */
    public final int cutoffSeconds;

    /** The linked targets */
    public final LinkedPointSet targets;

    /** the profilerequest (used for walk speed etc.) */
    public final ProfileRequest request;

    public PerTargetPropagater (int[][] travelTimesToStopsEachIteration, int[] nonTransitTravelTimesToTargets, LinkedPointSet targets, ProfileRequest request, int cutoffSeconds) {
        this.travelTimesToStopsEachIteration = travelTimesToStopsEachIteration;
        this.nonTransitTravelTimesToTargets = nonTransitTravelTimesToTargets;
        this.targets = targets;
        this.request = request;
        this.cutoffSeconds = cutoffSeconds;
    }

    private void propagate (Reducer reducer, TravelTimeReducer travelTimeReducer) {
        boolean saveTravelTimes = travelTimeReducer != null;
        targets.makePointToStopDistanceTablesIfNeeded();

        long startTimeMillis = System.currentTimeMillis();
        // avoid float math in loop below
        // float math was previously observed to slow down this loop, however it's debatable whether that was due to
        // casts, the operations themselves, or the fact that the operations were being completed with doubles rather
        // than floats.
        int speedMillimetersPerSecond = (int) (request.walkSpeed * 1000);

        boolean[] perIterationResults = new boolean[travelTimesToStopsEachIteration.length];
        int[] perIterationTravelTimes = saveTravelTimes ? new int[travelTimesToStopsEachIteration.length] : null;

        // Invert the travel times to stops array, to provide better memory locality in the tight loop below. Confirmed
        // that this provides a significant speedup, which makes sense; Java doesn't have true multidimensional arrays
        // but rather represents int[][] as Object[int[]], which means that each of the arrays "on the inside" is stored
        // separately in memory and may not be contiguous, also meaning the CPU can't efficiently predict and prefetch
        // what we need next. When we invert the array, we then have all travel times to a particular stop for all iterations
        // in a single array. The CPU will only page the data that is relevant for the current target, i.e. the travel
        // times to nearby stops. Since we are also looping over the targets in a geographic manner (in row-major order),
        // it is likely the stops relevant to a particular target will already be in memory from the previous target.
        // This should not increase memory consumption very much as we're only storing the travel times to stops. The
        // Netherlands has about 70,000 stops, if you do 1,000 iterations to 70,000 stops, the array being duplicated is
        // only 70,000 * 1000 * 4 bytes per int ~= 267 megabytes. I don't think it will be worthwhile to change the
        // algorithm to output already-transposed data as that will create other memory locality problems (since the
        // pathfinding algorithm solves one iteration for all stops simultaneously).
        int[][] invertedTravelTimesToStops = new int[travelTimesToStopsEachIteration[0].length][travelTimesToStopsEachIteration.length];

        for (int iteration = 0; iteration < travelTimesToStopsEachIteration.length; iteration++) {
            for (int stop = 0; stop < travelTimesToStopsEachIteration[0].length; stop++) {
                invertedTravelTimesToStops[stop][iteration] = travelTimesToStopsEachIteration[iteration][stop];
            }
        }

        for (int targetIdx = 0; targetIdx < targets.size(); targetIdx++) {
            // clear previous results, fill with whether target is reached within the cutoff without transit (which does
            // not vary with monte carlo draw)
            boolean targetReachedWithoutTransit = nonTransitTravelTimesToTargets[targetIdx] < cutoffSeconds;
            Arrays.fill(perIterationResults, targetReachedWithoutTransit);
            if (saveTravelTimes) {
                Arrays.fill(perIterationTravelTimes, nonTransitTravelTimesToTargets[targetIdx]);
            }

            if (targetReachedWithoutTransit && !saveTravelTimes) {
                // if the target is reached without transit, there's no need to do any propagation as the array cannot
                // change
                reducer.accept(targetIdx, perIterationResults);
                continue;
            }

            TIntIntMap pointToStopDistanceTable = targets.pointToStopDistanceTables.get(targetIdx);

            // all variables used in lambdas must be "effectively final"; arrays are effectively final even if their
            // values change
            boolean[] targetEverReached = new boolean[] { nonTransitTravelTimesToTargets[targetIdx] <= cutoffSeconds };

            // don't try to propagate transit if there are no nearby transit stops,
            // but still call the reducer below with the non-transit times, because you can walk even where there is no
            // transit
            if (pointToStopDistanceTable != null) {
                pointToStopDistanceTable.forEachEntry((stop, distanceMillimeters) -> {
                    for (int iteration = 0; iteration < perIterationResults.length; iteration++) {
                        int timeAtStop = invertedTravelTimesToStops[stop][iteration];

                        if (timeAtStop > cutoffSeconds || saveTravelTimes && timeAtStop > perIterationTravelTimes[iteration]) continue; // avoid overflow

                        int timeAtTargetThisStop = timeAtStop + distanceMillimeters / speedMillimetersPerSecond;

                        if (timeAtTargetThisStop < cutoffSeconds) {
                            if (saveTravelTimes) {
                                if (timeAtTargetThisStop < perIterationTravelTimes[iteration]) {
                                    perIterationTravelTimes[iteration] = timeAtTargetThisStop;
                                    targetEverReached[0] = true;
                                }
                            } else {
                                perIterationResults[iteration] = true;
                                targetEverReached[0] = true;
                            }
                        }
                    }
                    return true;
                });
            }

            if (saveTravelTimes) travelTimeReducer.accept(targetIdx, perIterationTravelTimes);
            else if (targetEverReached[0]) reducer.accept(targetIdx, perIterationResults);
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOG.info("Propagating {} iterations from {} stops to {} targets took {}s",
                travelTimesToStopsEachIteration.length,
                travelTimesToStopsEachIteration[0].length,
                targets.size(),
                totalTimeMillis / 1000d
                );
    }

    public void propagate (Reducer reducer) {
        propagate(reducer, null);
    }

    public void propagateTimes (TravelTimeReducer reducer) {
        propagate(null, reducer);
    }

    public static interface Reducer {
        public void accept (int targetIndex, boolean[] targetReachedWithinCutoff);
    }

    public interface TravelTimeReducer {
        public void accept (int targetIndex, int[] travelTimesForTargets);
    }
}
