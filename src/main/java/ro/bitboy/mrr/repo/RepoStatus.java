package ro.bitboy.mrr.repo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What mrr knows about one project's mirror, as the API and the project page report it.
 *
 * <p>Deliberately says what it does not have as well as what it does: a review that ran
 * without the mirror should be explainable from this row rather than from the logs.
 */
@Schema(description = "The state of a project's local mirror and its co-change index. Absent "
        + "or failed is a normal state, not an outage: reviews continue over the API and the "
        + "tools that need a mirror are simply not offered.")
public record RepoStatus(
        @Schema(description = "ABSENT | CLONING | READY | FAILED | TOO_LARGE | EVICTED")
        RepoState state,
        @Schema(nullable = true) String defaultBranch,
        @Schema(description = "Commit the last fetch left the mirror at.", nullable = true)
        String fetchedSha,
        @Schema(nullable = true) Instant fetchedAt,
        long sizeBytes,
        @Schema(nullable = true) Instant lastUsedAt,
        @Schema(description = "Why the last clone or fetch failed, if it did.", nullable = true)
        String lastError,
        @Schema(description = "NONE | INDEXING | READY | PARTIAL | FAILED", nullable = true)
        String coChangeState,
        @Schema(description = "Non-sweep commits folded into the co-change index.")
        long coChangeCommits,
        @Schema(description = "Commits dropped because they touched more paths than the "
                + "sweep threshold. A large share here means the threshold wants tuning.")
        long coChangeSkipped,
        @Schema(description = "Oldest commit the index covers.", nullable = true)
        String coChangeFrom,
        @Schema(description = "False while a backfill is incomplete, so a thin answer can be "
                + "discounted rather than trusted.")
        boolean historyComplete) {

    public static RepoStatus absent() {
        return new RepoStatus(RepoState.ABSENT, null, null, null, 0, null, null,
                "NONE", 0, 0, null, false);
    }
}
