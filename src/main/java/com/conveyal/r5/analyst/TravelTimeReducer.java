package com.conveyal.r5.analyst;

import com.beust.jcommander.ParameterException;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TimeGridWriter;
import com.conveyal.r5.analyst.cluster.TravelTimeResult;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.profile.FastRaptorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Given a bunch of travel times from an origin to a single destination point, this collapses that long list into a
 * limited number of percentiles, then optionally accumulates that destination's opportunity count into the
 * appropriate cumulative opportunities accessibility indicators at that origin.
 */
public class TravelTimeReducer {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeReducer.class);

    /**
     * Maximum total travel time, above which a destination should be considered unreachable. Note the logic in
     * analysis-backend AnalysisRequest, which sets this to the requested value for regional analyses, but keeps
     * it at the default value from R5 ProfileRequest for single-point requests (which allow adjusting the cutoff
     * after results have been calculated).
     */
    private final int maxTripDurationMinutes;

    private boolean calculateAccessibility;

    private boolean calculateTravelTimes;

    /**
     * Cumulative opportunities accessibility at this one particular origin.
     * May be null if we're only recording travel times.
     */
    private AccessibilityResult accessibilityResult = null;

    /** Reduced (e.g. at one percentile) travel time results. May be null if we're only recording accessibility. */
    private TravelTimeResult travelTimeResult = null;

    private final int[] percentileIndexes;

    private final int nPercentiles;

    /**
     * The number of travel times we will record at each destination.
     * This is affected by the number of Monte Carlo draws requested and the departure time window.
     */
    private final int timesPerDestination;

    /**
     * Reduce travel time values to requested summary outputs for each origin. The type of output (a single
     * cumulative opportunity accessibility value per origin, or selected percentiles of travel times to all
     * destinations) is determined based on the provided task.
     *
     * If the task is a RegionalTask and does not include an originPointSetKey or a value of true for the
     * makeTauiSite flag, travel times will be reduced to an accessibility value per origin. If a RegionalTask
     * includes an originPointSetKey, travel times from the origins to the destinations of the destinationPointSetKey
     * will be retained. Accessibility values for freeform origin pointsets are not yet saved; this is marked as a
     * to-do below.
     *
     * The task is also used to determine the number of timesPerDestination, which depends on whether the  task
     * specifies an inRoutingFareCalculator. A non-null inRoutingFareCalculator is used as a flag for the
     * multi-criteria McRaptor router, which is relatively slow, so it relies on sampling (using a number of
     * departure times specified by task.monteCarloDraws). FastRaptorworker is fast enough to run Monte Carlo draws
     * within departure minutes, so it uses the monteCarloDraws parameter in a way that's consistent with its name.
     *
     * @param task task to be performed.
     */
    public TravelTimeReducer (AnalysisTask task) {

        this.maxTripDurationMinutes = task.maxTripDurationMinutes;

        // Set timesPerDestination depending on how waiting time/travel time variability will be sampled
        if (task.inRoutingFareCalculator != null) {
            // Calculating fares within routing (using the McRaptor router) is slow, so sample at different
            // departure times (rather than sampling multiple draws at every minute in the departure time window).
            this.timesPerDestination = task.monteCarloDraws;
        } else {
            if (task.monteCarloDraws == 0) {
                // HALF_HEADWAY boarding, returning a single travel time per departure minute per destination.
                this.timesPerDestination = task.getTimeWindowLengthMinutes();
            } else {
                // MONTE_CARLO boarding, using several different randomized schedules at each departure time.
                this.timesPerDestination = task.getTimeWindowLengthMinutes() * task.getMonteCarloDrawsPerMinute();
            }
        }

        this.nPercentiles = task.percentiles.length;

        // We pre-compute the indexes at which we'll find each percentile in a sorted list of the given length.
        this.percentileIndexes = new int[nPercentiles];
        for (int p = 0; p < nPercentiles; p++) {
            percentileIndexes[p] = findPercentileIndex(timesPerDestination, task.percentiles[p]);
        }

        // Decide whether we want to retain travel times to all destinations for this origin.
        // This is currently only used with regional tasks when origins are freeform pointsets.
        // This base TravelTimeResult class (as opposed to its subclass TimeGrid) does not have grid writing
        // capabilities, which are not needed or relevant in non-Taui regional analyses as they report directly
        // back to the broker in JSON.

        // Decide which elements we'll be calculating, retaining, and returning.
        calculateAccessibility = calculateTravelTimes = false;
        if (task instanceof TravelTimeSurfaceTask) {
            calculateTravelTimes = true;
        } else {
            RegionalTask regionalTask = (RegionalTask) task;
            if (regionalTask.recordAccessibility) {
                calculateAccessibility = true;
            }
            if (regionalTask.recordTimes || regionalTask.makeTauiSite) {
                calculateTravelTimes = true;
            }
        }

        // Instantiate and initialize objects to accumulate the kinds of results we expect to produce.
        // These are conditionally instantiated because they can consume a lot of memory.
        if (calculateAccessibility) {
            accessibilityResult = new AccessibilityResult(task);
        }
        if (calculateTravelTimes) {
            travelTimeResult = new TravelTimeResult(task);
        }
    }


    /**
     * Compute the index into a sorted list of N elements at which a particular percentile will be found. Our
     * method does not interpolate, it always reports a value actually appearing in the list of elements. That is
     * to say, the percentile will be found at an integer-valued index into the sorted array of elements. The
     * definition of a non-interpolated percentile is as follows: the smallest value in the list such that no more
     * than P percent of the data is strictly less than the value and at least P percent of the data is less than
     * or equal to that value. By this definition, the 100th percentile is the largest value in the list. See
     * https://en.wikipedia.org/wiki/Percentile#Definitions
     * <p>
     * The formula given on Wikipedia next to definition cited above uses ceiling for one-based indexes. It is
     * tempting to just truncate to ints instead of ceiling but this gives different results on integer
     * boundaries.
     */
    private static int findPercentileIndex(int nElements, double percentile) {
        return (int)(Math.ceil(percentile / 100 * nElements) - 1);
    }

    /**
     * Given a single unvarying travel time to a destination, replicate it to match the expected number of
     * percentiles, then record those n identical percentiles at the target.
     *
     * @param timeSeconds Single travel time, for results with no variation, e.g. from walking, biking, or driving.
     * @return the extracted travel times, in minutes. This is a hack to enable scoring paths in the caller.
     */
    public int[] recordUnvaryingTravelTimeAtTarget (int target, int timeSeconds){
        int[] travelTimesMinutes = new int[nPercentiles];
        Arrays.fill(travelTimesMinutes, convertToMinutes(timeSeconds));
        return recordTravelTimesForTarget(target, travelTimesMinutes);
    }

    /**
     * Given a list of travel times of the expected length, extract the requested percentiles, then record value for
     * target.
     * WARNING: this method destructively sorts the supplied times in place.
     * Their positions in the array will no longer correspond to the raptor iterations that produced them.
     * @param timesSeconds which will be destructively sorted in place to extract percentiles.
     * @return the extracted travel times, in minutes. This is a hack to enable scoring paths in the caller.
     */
    public int[] extractTravelTimesAndRecord (int target, int[] timesSeconds) {
        // Sort the times at each target and extract percentiles at the pre-calculated indexes.
        int[] percentileTravelTimesMinutes = new int[nPercentiles];
        if (timesSeconds.length == timesPerDestination) {
            // Instead of general purpose sort this could be done by performing a counting sort on the times,
            // converting them to minutes in the process and reusing the small histogram array (120 elements) which
            // should remain largely in processor cache. That's a lot of division though. Would need to be profiled.
            Arrays.sort(timesSeconds);
            for (int p = 0; p < nPercentiles; p++) {
                int timeSeconds = timesSeconds[percentileIndexes[p]];
                percentileTravelTimesMinutes[p] = convertToMinutes(timeSeconds);
            }
        } else {
            throw new ParameterException(timesSeconds.length + " iterations supplied; expected " + timesPerDestination);
        }
        return recordTravelTimesForTarget(target, percentileTravelTimesMinutes);
    }

    /**
     * Given a list of travel times of the expected length, store the extracted percentiles of travel time (if a and/or
     * accessibility values.
     * @return the extracted travel times, in minutes. This is a hack to enable scoring paths in the caller.
     */
    private int[] recordTravelTimesForTarget (int target, int[] percentileTravelTimesMinutes) {
        if (percentileTravelTimesMinutes.length != nPercentiles) {
            throw new ParameterException(percentileTravelTimesMinutes.length + " percentile values supplied; expected" +
                    " " + nPercentiles);
        }
        if (calculateTravelTimes) {
            travelTimeResult.setTarget(target, percentileTravelTimesMinutes);
        }
        if (calculateAccessibility) {
            // This is only handling one grid at a time, needs to be adapted to handle multiple different extents.
            PointSet pointSet = accessibilityResult.destinationPointSets[0];
            double amount = pointSet.getOpportunityCount(target);
            for (int p = 0; p < nPercentiles; p++) {
                // Use of < here (as opposed to <=) matches the definition in JS front end,
                // and works well when truncating seconds to minutes.
                if (percentileTravelTimesMinutes[p] < maxTripDurationMinutes) {
                    accessibilityResult.incrementAccessibility(0, 0, p, amount);
                }
            }
        }
        return percentileTravelTimesMinutes;
    }

    /**
     * Convert the given timeSeconds to minutes. If that time equals or exceeds the maxTripDurationMinutes, instead
     * return a value indicating that the location is unreachable. The minutes to seconds conversion uses integer
     * division, which truncates toward zero. This approach is correct for use in accessibility analysis, where we
     * are always testing whether a travel time is less than a certain threshold value. For example, all travel times
     * between 59 and 60 minutes will truncate to 59, and will correctly return true for the expression (t < 60
     * minutes). We are converting seconds to minutes before we export a binary format mainly to narrow the times so
     * they fit into single bytes (though this also reduces entropy and makes compression more effective). Arguably
     * this is coupling the backend too closely to the frontend (which makes use of UInt8 typed arrays); the front
     * end could in principle receive a more general purpose format using wider or variable width integers.
     */
    private int convertToMinutes (int timeSeconds) {
        if (timeSeconds == FastRaptorWorker.UNREACHED) return FastRaptorWorker.UNREACHED;
        int timeMinutes = timeSeconds / FastRaptorWorker.SECONDS_PER_MINUTE;
        if (timeMinutes < maxTripDurationMinutes) {
            return timeMinutes;
        } else {
            return FastRaptorWorker.UNREACHED;
        }
    }


    /**
     * If no travel times to destinations have been streamed in by calling recordTravelTimesForTarget, the
     * TimeGrid will have a buffer full of UNREACHED. This allows shortcutting around
     * routing and propagation when the origin point is not connected to the street network.
     */
    public OneOriginResult finish () {
        return new OneOriginResult(travelTimeResult, accessibilityResult);
    }

}
