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
        Double meanReadyDurationSeconds,
        Map<String, Long> failuresByKind,
        List<ProjectRow> projects
) {
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
