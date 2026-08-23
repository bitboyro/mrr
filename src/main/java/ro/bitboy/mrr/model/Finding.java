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
        @Schema(description = "GitLab username of the human who resolved the thread, when the "
                + "state is APPROVED. Null for OPEN and for ADDRESSED.",
                example = "romanbotnari", nullable = true)
        String approvedBy
) {

    /** A fresh finding: nobody has approved it yet. */
    public Finding(String id, String reviewId, String projectId, long mrIid, String filePath,
                   Integer line, Severity severity, String title, String body,
                   String discussionId, String noteId, boolean inline, State state,
                   Instant createdAt, Instant resolvedAt) {
        this(id, reviewId, projectId, mrIid, filePath, line, severity, title, body,
                discussionId, noteId, inline, state, createdAt, resolvedAt, null);
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
            APPROVED — a human resolved the thread on GitLab, accepting the finding as it \
            stands. mrr records who and keeps the thread closed.""")
    public enum State {
        OPEN,
        ADDRESSED,
        APPROVED;

        /** OPEN is the only state that still counts against the merge request. */
        public boolean isClosed() {
            return this != OPEN;
        }
    }
}
