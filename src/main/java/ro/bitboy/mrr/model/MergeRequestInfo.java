package ro.bitboy.mrr.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What GitLab told us about the merge request when this review ran — a snapshot, not a live
 * view. Retitling an MR does not rewrite the reviews already recorded against it.
 *
 * <p>Three different people show up here and they are routinely conflated:
 *
 * <ul>
 *   <li>{@code authorName} — who owns the MR. The webhook carries only a numeric
 *       {@code author_id}, so this is filled in from the API during the review.
 *   <li>{@code actorName} — who triggered <i>this</i> delivery, i.e. who pushed or reopened.
 *   <li>{@code commitAuthorName} — who wrote the head commit. Often not either of the above,
 *       on a rebase or a cherry-pick.
 * </ul>
 */
@Schema(description = """
        The merge request as GitLab described it when this review ran.

        A snapshot, not a live view: retitle the MR and push again and you get a second \
        review carrying the new title, with the first left untouched. That history is the \
        point — it is what lets you ask why a review said what it said.

        Note the three different people. `author` owns the MR. `actor` triggered this \
        particular delivery. `commitAuthor` wrote the head commit. On a rebase or a \
        cherry-pick these are routinely three different names.""")
public record MergeRequestInfo(
        @Schema(description = "MR title at review time.",
                example = "Add batch CSV reporting for the nightly job") String title,
        @Schema(description = "Web URL of the MR.") String url,
        @Schema(description = "opened | merged | closed", example = "opened") String state,
        @Schema(description = "The event that triggered this review.", example = "update") String action,
        @Schema(description = "Null when GitLab did not say.", nullable = true) Boolean draft,
        @Schema(example = "fix/batch-reporting") String sourceBranch,
        @Schema(example = "main") String targetBranch,
        @Schema(description = "Who owns the MR. Filled in from the API — the webhook carries only "
                + "a numeric author_id.", nullable = true) String authorName,
        @Schema(nullable = true) String authorUsername,
        @Schema(description = "Who triggered this delivery — pushed, opened, reopened.", nullable = true)
        String actorName,
        @Schema(nullable = true) String actorUsername,
        @Schema(description = "Message of the head commit.", nullable = true) String commitMessage,
        @Schema(description = "Who wrote the head commit.", nullable = true) String commitAuthorName,
        @Schema(nullable = true) String commitAuthorEmail,
        @Schema(description = "When the head commit was authored.", nullable = true) Instant commitAt,
        @Schema(description = "Label names as a JSON array.", example = "[\"backend\"]", nullable = true)
        String labels,
        @Schema(description = "Files in the diff. Filled in from the API.", nullable = true)
        Integer filesChanged,
        @Schema(description = "Commits on the MR when this review ran.", nullable = true)
        Integer commitCount,
        @Schema(description = "Assignees, one `Name (@user)` per line.", nullable = true)
        String assignees,
        @Schema(description = "Reviewers GitLab lists on the MR, same shape as assignees.",
                nullable = true)
        String reviewers,
        @Schema(description = "GitLab `detailed_merge_status`, or `merge_status` when that is "
                + "all the payload had. `need_rebase` means the source is behind the target.",
                example = "need_rebase", nullable = true)
        String detailedMergeStatus,
        @Schema(description = "Commits the source branch is behind the target. GitLab only "
                + "returns this when asked; null means we have not seen a count.",
                nullable = true)
        Integer divergedCommitsCount,
        @Schema(description = "True when GitLab reports conflicts with the target.",
                nullable = true)
        Boolean hasConflicts
) {

    public static MergeRequestInfo empty() {
        return new MergeRequestInfo(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /** Source is behind the target — GitLab will not merge until it is rebased. */
    public boolean behindTarget() {
        if (divergedCommitsCount != null && divergedCommitsCount > 0) {
            return true;
        }
        return detailedMergeStatus != null && "need_rebase".equalsIgnoreCase(detailedMergeStatus);
    }

    public boolean conflicts() {
        if (Boolean.TRUE.equals(hasConflicts)) {
            return true;
        }
        return detailedMergeStatus != null && "conflict".equalsIgnoreCase(detailedMergeStatus);
    }

    /**
     * Overlays what the API told us on top of what the webhook did. The API is authoritative
     * for the author and the file count; the webhook is authoritative for the action and the
     * actor, which the API has no notion of.
     */
    public MergeRequestInfo enrichedWith(String apiTitle, String apiUrl,
                                         String apiAuthorName, String apiAuthorUsername,
                                         String apiSourceBranch, String apiTargetBranch,
                                         Integer apiFilesChanged) {
        return new MergeRequestInfo(
                pick(apiTitle, title),
                pick(apiUrl, url),
                state,
                action,
                draft,
                pick(apiSourceBranch, sourceBranch),
                pick(apiTargetBranch, targetBranch),
                pick(apiAuthorName, authorName),
                pick(apiAuthorUsername, authorUsername),
                actorName,
                actorUsername,
                commitMessage,
                commitAuthorName,
                commitAuthorEmail,
                commitAt,
                labels,
                apiFilesChanged == null ? filesChanged : apiFilesChanged,
                commitCount,
                assignees,
                reviewers,
                detailedMergeStatus,
                divergedCommitsCount,
                hasConflicts);
    }

    public MergeRequestInfo withGitMeta(Integer apiCommitCount, String apiAssignees,
                                        String apiReviewers) {
        return withGitMeta(apiCommitCount, apiAssignees, apiReviewers, null, null, null);
    }

    public MergeRequestInfo withGitMeta(Integer apiCommitCount, String apiAssignees,
                                        String apiReviewers, String apiMergeStatus,
                                        Integer apiDiverged, Boolean apiConflicts) {
        return new MergeRequestInfo(
                title, url, state, action, draft, sourceBranch, targetBranch,
                authorName, authorUsername, actorName, actorUsername,
                commitMessage, commitAuthorName, commitAuthorEmail, commitAt, labels,
                filesChanged,
                apiCommitCount == null ? commitCount : apiCommitCount,
                pick(apiAssignees, assignees),
                pick(apiReviewers, reviewers),
                pick(apiMergeStatus, detailedMergeStatus),
                apiDiverged == null ? divergedCommitsCount : apiDiverged,
                apiConflicts == null ? hasConflicts : apiConflicts);
    }

    private static String pick(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
