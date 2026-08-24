package ro.bitboy.mrr.forge;

import java.util.List;

/**
 * Advisory metadata stamped onto the review snapshot: commit count, who is on the request, and
 * how the host thinks it will merge.
 *
 * <p>{@code detailedMergeStatus}, {@code divergedCommitsCount} and {@code hasConflicts} are
 * GitLab-shaped. Forgejo and GitHub have no matching fields, so their adapters derive what they
 * can and leave the rest null. Null is acceptable — the UI renders these as warnings, not as
 * review inputs, and it simply shows nothing.
 */
public record GitMeta(Integer commitCount, List<String> assignees, List<String> reviewers,
                      String detailedMergeStatus, Integer divergedCommitsCount,
                      Boolean hasConflicts) {

    public GitMeta(Integer commitCount, List<String> assignees, List<String> reviewers) {
        this(commitCount, assignees, reviewers, null, null, null);
    }

    public String assigneesText() {
        return assignees == null || assignees.isEmpty() ? null : String.join("\n", assignees);
    }

    public String reviewersText() {
        return reviewers == null || reviewers.isEmpty() ? null : String.join("\n", reviewers);
    }
}
