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
        from a human clicking Approve in GitLab. `MERGED` / `CLOSED` follow GitLab's MR \
        state, not finding checkboxes.""")
public record MrStatus(
        String projectId,
        long mrIid,
        Status currentStatus,
        @Schema(nullable = true) String latestReviewId,
        @Schema(nullable = true) String latestHeadSha,
        int openFindings,
        boolean mrrApproved,
        @Schema(nullable = true) String mrrApprovedSha,
        @Schema(description = "Owner-written notes included in the next review of this MR. "
                + "Blank means none.", nullable = true) String userContext,
        Instant updatedAt,
        @Schema(description = "GitLab title from the latest review, when we have one.",
                nullable = true) String title,
        @Schema(description = "AD_HOC is every merge request's default: the agent reviews, the "
                + "status follows the findings, no ceremony. FORMAL adds named participants "
                + "who must sign off before mrr will call it clean.") Mode mode,
        @Schema(description = "Where the review session stands, as distinct from where the code "
                + "stands.") SessionState sessionState
) {

    /** Unstated is ad-hoc, which is what every merge request was before sessions existed. */
    public MrStatus {
        if (mode == null) mode = Mode.AD_HOC;
        if (sessionState == null) sessionState = SessionState.UNDER_REVIEW;
    }

    /** The pre-session shape, kept so nothing that does not care about one has to say so. */
    public MrStatus(String projectId, long mrIid, Status currentStatus, String latestReviewId,
                    String latestHeadSha, int openFindings, boolean mrrApproved,
                    String mrrApprovedSha, String userContext, Instant updatedAt, String title) {
        this(projectId, mrIid, currentStatus, latestReviewId, latestHeadSha, openFindings,
                mrrApproved, mrrApprovedSha, userContext, updatedAt, title, Mode.AD_HOC,
                SessionState.UNDER_REVIEW);
    }

    /**
     * The merge gate, in one place.
     *
     * <p>Two callers count these two numbers from different starting points — the reviewer has
     * them in hand at the end of a review, and the session endpoints have to go and ask — but
     * the rule they apply must not be able to drift apart, because a status page that disagreed
     * with what auto-approve did would be the ledger lying about itself.
     *
     * @param open          findings still standing, which includes ones held for release
     * @param outstanding   required participants who have not signed off; always 0 when ad-hoc
     */
    public static Status gate(int open, int outstanding) {
        return open == 0 && outstanding == 0 ? Status.CLEAN : Status.NEEDS_WORK;
    }

    @Schema(description = "AD_HOC — today's behaviour, and the default. FORMAL — participants "
            + "and sign-off, opted into per merge request.")
    public enum Mode {
        AD_HOC,
        FORMAL
    }

    @Schema(description = "UNDER_REVIEW: the session is live. SUMMARIZED: somebody wrote it up, "
            + "and findings can still move. CLOSED: the review is over — which is not the same "
            + "as the request being merged or closed, that is the forge's call and shows in "
            + "`currentStatus`.")
    public enum SessionState {
        UNDER_REVIEW,
        SUMMARIZED,
        CLOSED
    }

    public enum Status {
        REVIEWING,
        NEEDS_WORK,
        CLEAN,
        FAILED,
        CANCELLED,
        MERGED,
        CLOSED;

        /** GitLab already ended this MR — findings are history, not a live merge gate. */
        public boolean isClosed() {
            return this == MERGED || this == CLOSED || this == CANCELLED;
        }
    }

    @Schema(description = "One merge request's cycle: live status, every review, every finding, "
            + "every reply, and the event log behind the findings' states.")
    public record Cycle(
            MrStatus status,
            List<Review> reviews,
            List<Finding> findings,
            List<Reply> replies,
            @Schema(description = "Every event on every finding of this merge request, oldest "
                    + "first. A finding with none predates the log rather than having no "
                    + "history.")
            List<FindingEvent> events,
            @Schema(description = "Who is in the room. Empty on an ad-hoc cycle, which is most "
                    + "of them.")
            List<SessionParticipant> participants
    ) {
    }

    @Schema(description = "A page of merge-request cycles, newest iid first.")
    public record Page(List<MrStatus> items, int total, int offset, int limit) {
    }
}
