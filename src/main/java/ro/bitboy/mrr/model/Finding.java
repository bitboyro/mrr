package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One thing the reviewer found, and the GitLab thread carrying it.
 *
 * <p>Findings outlive the review that produced them. When a later commit addresses one, the
 * thread opened by the <i>earlier</i> review is what gets resolved — so a finding is keyed to
 * the merge request, not just to the review that first reported it.
 */
@Schema(description = """
        One finding, and the merge-request thread that carries it.

        A finding outlives the review that produced it: when a later commit fixes the \
        problem, mrr resolves the thread the earlier review opened rather than posting \
        "this is now fixed" as a new comment.""")
public record Finding(
        String id,
        @Schema(description = "The review that first reported it.") String reviewId,
        String projectId,
        long mrIid,
        @Schema(description = "File it was found in.",
                example = "src/main/java/ro/bitboy/testrepo/ReportService.java", nullable = true)
        String filePath,
        @Schema(description = "Line in the new file. Null when the model gave no line, or the "
                + "line was not part of the diff.", nullable = true) Integer line,
        Severity severity,
        @Schema(description = "One-line statement of the problem.",
                example = "Off-by-one overruns the array on every row") String title,
        @Schema(description = "The full finding as posted, in Markdown.") String body,
        @Schema(description = "The GitLab thread carrying this finding.", nullable = true)
        String discussionId,
        @Schema(nullable = true) String noteId,
        @Schema(description = "True when the thread attached to a specific diff line; false when "
                + "the position was rejected and it fell back to the summary thread.")
        boolean inline,
        State state,
        Instant createdAt,
        @Schema(description = "When it stopped standing \u2014 a later commit addressed it, or a "
                + "human resolved the thread.", nullable = true)
        Instant resolvedAt,
        @Schema(description = "Forge username of the human who resolved the thread, when the "
                + "state is APPROVED. Null for OPEN and for ADDRESSED.",
                example = "romanbotnari", nullable = true)
        String approvedBy,
        @Schema(description = "Who filed it. Null on findings from before origins existed, "
                + "which were all AGENT.") Origin origin,
        @Schema(description = "mrr user id that filed it, when it was filed through mrr.",
                nullable = true) String authorUser,
        @Schema(description = "Forge username that filed it, when it came from the forge.",
                nullable = true) String authorForge,
        @Schema(description = "The concern this came from — security, tests, performance — as "
                + "the reviewer named it. Self-reported in one pass, not a separate agent. "
                + "Null when it did not say, and on every human finding.",
                example = "security", nullable = true) String agentRole
) {

    /** Unstated origin is AGENT: every finding predating the column was the reviewer's. */
    public Finding {
        if (origin == null) origin = Origin.AGENT;
    }

    /** A fresh agent finding: nobody has approved it, and the reviewer wrote it. */
    public Finding(String id, String reviewId, String projectId, long mrIid, String filePath,
                   Integer line, Severity severity, String title, String body,
                   String discussionId, String noteId, boolean inline, State state,
                   Instant createdAt, Instant resolvedAt) {
        this(id, reviewId, projectId, mrIid, filePath, line, severity, title, body,
                discussionId, noteId, inline, state, createdAt, resolvedAt, null,
                Origin.AGENT, null, null, null);
    }

    /** An authored finding with no concern attached, which is every human one. */
    public Finding(String id, String reviewId, String projectId, long mrIid, String filePath,
                   Integer line, Severity severity, String title, String body,
                   String discussionId, String noteId, boolean inline, State state,
                   Instant createdAt, Instant resolvedAt, String approvedBy, Origin origin,
                   String authorUser, String authorForge) {
        this(id, reviewId, projectId, mrIid, filePath, line, severity, title, body,
                discussionId, noteId, inline, state, createdAt, resolvedAt, approvedBy,
                origin, authorUser, authorForge, null);
    }

    /**
     * Whether mrr opened this finding's thread, and may therefore resolve and reopen it.
     *
     * <p>A finding that arrived from a comment somebody left on the forge is attached to
     * <em>their</em> thread. mrr tracks it — it counts against the merge gate, a later review
     * judges it — but pressing Resolve on a conversation another person started is their act,
     * not mrr's. Every other finding sits on a thread mrr opened: the reviewer's, and the ones
     * filed through mrr over the operator token.
     *
     * <p>Derived rather than stored, because {@code authorForge} being set <em>is</em> what it
     * means to have come in from the forge. If a later path ever files from the forge onto a
     * thread mrr opened, this is the one place that has to learn about it.
     */
    public boolean mrrOpenedThread() {
        return authorForge == null;
    }

    /**
     * Whether an agent may close this one on its own.
     *
     * <p>It may not close a human's: a later review can say the code looks fixed, but the
     * person who reported it is the one who decides that it is. An agent closing its own
     * finding is the loop that already works and stays untouched.
     */
    public boolean agentMayClose() {
        return origin != Origin.HUMAN;
    }

    @Schema(description = "AGENT — the reviewer filed it. HUMAN — a person did, from mrr or "
            + "from the forge. Both are the same kind of object and count the same against "
            + "the merge gate; the difference is who may close one.")
    public enum Origin {
        AGENT,
        HUMAN
    }

    @Schema(description = "How bad it is, as judged by the reviewer.")
    public enum Severity {
        BLOCKER,
        MAJOR,
        MINOR;

        /** Anything unrecognised is a MINOR rather than a dropped finding. */
        public static Severity parse(String raw) {
            if (raw == null) return MINOR;
            String value = raw.trim().toUpperCase();
            for (Severity severity : values()) {
                if (severity.name().equals(value)) return severity;
            }
            return switch (value) {
                case "CRITICAL", "HIGH", "ERROR" -> BLOCKER;
                case "MEDIUM", "WARNING" -> MAJOR;
                default -> MINOR;
            };
        }
    }

    @Schema(description = """
            OPEN — still standing as of the most recent review.
            ADDRESSED — a later commit fixed it; mrr resolved the thread.
            APPROVED — a human resolved the thread on the forge, accepting the finding as it \
            stands. mrr records who and keeps the thread closed.
            WONT_FIX — a human decided it is real and is not being fixed. The record says who \
            accepted the risk.
            DUPLICATE — a human closed it as another finding's twin.
            PENDING — the reviewer drafted it and it has not reached the forge, because this \
            project holds findings for a human to release. It still counts against the merge \
            gate: nobody has decided it is wrong, only that nobody has looked yet.
            DISCARDED — somebody read a pending finding before it was posted and decided it \
            was not worth raising. Deliberately not WONT_FIX, where the problem is real and \
            the risk was accepted on the record.

            There is no DISPUTED state on purpose. A contested finding stays OPEN and keeps \
            counting against the merge gate; the disagreement is a `DISPUTED` event on it. A \
            sixth state would be either a veto anybody could hold indefinitely, or a green \
            merge request with a live disagreement under it.""")
    public enum State {
        OPEN,
        PENDING,
        ADDRESSED,
        APPROVED,
        WONT_FIX,
        DUPLICATE,
        DISCARDED;

        /**
         * Whether this finding is settled. OPEN and PENDING are not: one is a problem nobody
         * has fixed, the other a problem nobody has looked at, and neither is a reason to let
         * a merge request read as clean.
         */
        public boolean isClosed() {
            return this != OPEN && this != PENDING;
        }

        /** Drafted, and waiting on a person to release it to the forge. */
        public boolean isPending() {
            return this == PENDING;
        }
    }
}
