package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One model answer on a finding thread. Distinct from a {@link Review}: a review is a
 * commit, a reply is a human talking back to a finding mrr already posted.
 */
@Schema(description = """
        One reply mrr posted on a finding thread. `prompt` and `trace` are omitted from \
        list/cycle responses — fetch `GET /api/replies/{id}` for the full transcript.""")
public record Reply(
        String id,
        String projectId,
        long mrIid,
        String discussionId,
        @Schema(nullable = true) String findingId,
        @Schema(description = "What was sent to the model. Null in list responses.", nullable = true)
        String prompt,
        @Schema(description = "The answer that was posted.", nullable = true) String response,
        @Schema(nullable = true) AgentTrace trace,
        Instant createdAt
) {

    public Reply summary() {
        return new Reply(id, projectId, mrIid, discussionId, findingId,
                null, response, null, createdAt);
    }
}
