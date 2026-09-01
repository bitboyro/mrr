package ro.bitboy.mrr.model;

import java.util.List;
import java.util.Map;

public record InstanceStats(
        long mrsProcessed,
        long reviewsTotal,
        Map<String, Long> reviewsByState,
        long inFlight,
        long pendingOrProcessing,
        TokenTotals tokens,
        TokenTotals tokensLast7Days,
        long findingsOpen,
        long findingsAddressed,
        long findingsApproved,
        Double meanReadyDurationSeconds,
        Map<String, Long> failuresByKind,
        /**
         * Which tools the reviewer actually calls, all time and over the last week.
         *
         * <p>Counted from executed calls rather than from project settings, because a tool
         * nobody calls and a tool nobody enabled look identical in the settings and want very
         * different responses.
         */
        Map<String, Long> toolCalls,
        Map<String, Long> toolCallsLast7Days,
        /**
         * How those calls ended: OK, ERROR, REFUSED, QUOTA or DEGRADED.
         *
         * <p>The one that earns its place. A search called forty times that answers QUOTA
         * thirty of them means the allowance is set wrong, and no other number here would
         * show that.
         */
        Map<String, Long> toolOutcomes,
        List<ProjectRow> projects
) {

    /** For call sites that predate the tool counters. */
    public InstanceStats(long mrsProcessed, long reviewsTotal, Map<String, Long> reviewsByState,
                         long inFlight, long pendingOrProcessing, TokenTotals tokens,
                         TokenTotals tokensLast7Days, long findingsOpen, long findingsAddressed,
                         long findingsApproved, Double meanReadyDurationSeconds,
                         Map<String, Long> failuresByKind, List<ProjectRow> projects) {
        this(mrsProcessed, reviewsTotal, reviewsByState, inFlight, pendingOrProcessing, tokens,
                tokensLast7Days, findingsOpen, findingsAddressed, findingsApproved,
                meanReadyDurationSeconds, failuresByKind, Map.of(), Map.of(), Map.of(), projects);
    }
    public record TokenTotals(long input, long output, long cacheRead) {
    }

    public record ProjectRow(
            String projectId,
            String name,
            long mrs,
            long reviews,
            long inputTokens,
            long outputTokens
    ) {
    }
}
