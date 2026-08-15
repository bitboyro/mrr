package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Where one merge request stands right now, across every review of it.
 *
 * <p>A {@link Review} is a snapshot of one commit. This is the live row the UI polls:
 * still reviewing, still has findings, or clean enough to approve.
 */
@Schema(description = """
        Live status of one merge request. Distinct from a Review, which is a snapshot of \
        one commit. `mrrApproved` is true only when mrr itself approved — never inferred \
        from a human clicking Approve in GitLab.""")
public record MrStatus(
        String projectId,
        long mrIid,
        Status currentStatus,
        @Schema(nullable = true) String latestReviewId,
        @Schema(nullable = true) String latestHeadSha,
        int openFindings,
        boolean mrrApproved,
        @Schema(nullable = true) String mrrApprovedSha,
        Instant updatedAt
) {

    public enum Status {
        REVIEWING,
        NEEDS_WORK,
        CLEAN,
        FAILED,
        CANCELLED
    }

    @Schema(description = "One merge request's cycle: live status, every review, every finding, every reply.")
    public record Cycle(
            MrStatus status,
            List<Review> reviews,
            List<Finding> findings,
            List<Reply> replies
    ) {
    }
}
