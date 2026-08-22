package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = """
        One review of one commit on one merge request.

        Keyed on `(projectId, mrIid, headSha)` — that triple is what makes redelivered \
        webhooks free. The `mr` block is a snapshot taken when the review ran, not a live \
        view of the merge request.""")
public record Review(
        String id,
        String projectId,
        @Schema(description = "The MR's `iid` — its number within the project, the one in the URL.",
                example = "1") long mrIid,
        @Schema(description = "Commit reviewed. A new push means a new SHA and so a new review.")
        String headSha,
        State state,
        @Schema(description = "The merge request as it stood at this commit.") MergeRequestInfo mr,
        @Schema(description = "The webhook body, verbatim. Null in list responses.", nullable = true)
        String payload,
        @Schema(description = "The initial system + user prompt sent to the model. Null in "
                + "list responses. Tool calls live on `trace`, not here.", nullable = true) String prompt,
        @Schema(description = "The review the model produced. Null in list responses. May be set "
                + "even on a FAILED review — the model succeeded and posting did not.", nullable = true)
        String response,
        @Schema(description = "GitLab id of the posted comment.", nullable = true) String noteId,
        @Schema(description = "GitLab id of the thread holding it. Both are needed to edit.",
                nullable = true) String discussionId,
        @Schema(description = "Why it failed, when it did.", nullable = true) String error,
        @Schema(description = "QUOTA | RATE_LIMIT | AUTH | PROVIDER | OUTPUT | MCP | GITLAB | REFUSAL. "
                + "Set when the review failed before posting.", nullable = true)
        FailureKind errorKind,
        Instant createdAt,
        @Schema(description = "Set when the review reached a terminal state.", nullable = true)
        Instant finishedAt,
        @Schema(description = "How many model turns actually ran. 0 until the agent starts. "
                + "Kept on list responses.")
        int agentTurns,
        @Schema(description = "The conversation after the initial prompt: assistant turns, "
                + "tool calls, and truncated tool results. Null in list responses.", nullable = true)
        AgentTrace trace
) {

    @Schema(description = """
            PENDING — claimed, queued.
            PROCESSING — diff fetched, model running.
            READY — reviewed and posted. Terminal.
            FAILED — see `error`. Terminal.
            CANCELLED — the MR was merged or closed while the review was running, so the \
            result was never posted. Terminal.

            Stop polling on any terminal state.""")
    public enum State {
        PENDING,
        PROCESSING,
        READY,
        FAILED,
        CANCELLED;

        public boolean isTerminal() {
            return this == READY || this == FAILED || this == CANCELLED;
        }
    }

    /**
     * The list view. Drops the bulky columns — payload, prompt, response, and trace —
     * while keeping {@code agentTurns} and the merge-request snapshot.
     */
    public Review summary() {
        return new Review(id, projectId, mrIid, headSha, state, mr, null, null, null,
                noteId, discussionId, error, errorKind, createdAt, finishedAt, agentTurns, null);
    }

    public Review withMr(MergeRequestInfo updated) {
        return new Review(id, projectId, mrIid, headSha, state, updated, payload, prompt,
                response, noteId, discussionId, error, errorKind, createdAt, finishedAt,
                agentTurns, trace);
    }
}
