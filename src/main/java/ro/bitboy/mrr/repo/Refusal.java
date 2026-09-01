package ro.bitboy.mrr.repo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A structured "no", which is not the same as an error.
 *
 * <p>mrr's convention is that a tool error is an {@code "error: …"} string the model reads.
 * That stays, for the genuinely exceptional — malformed arguments, a parser that threw. But
 * running out of quota, or asking for a tool whose mirror is not ready, is a normal outcome
 * with a correct response, and dressing it as an error invites the model to retry it. A
 * refusal says what happened and what to do instead, and is recorded so a human reading the
 * review can see where it stopped looking.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Refusal(String refusal, String tool, String reason, Integer limit, Integer used,
                      String advice) {

    public static Refusal quotaExhausted(String tool, int limit) {
        return new Refusal("quota_exhausted", tool,
                "this unit has spent its allowance of " + tool + " calls", limit, limit,
                "Conclude from what you have, and say in the finding that the search was "
                        + "bounded rather than exhaustive.");
    }

    public static Refusal noWorkingCopy(String tool, RepoState state) {
        return new Refusal("working_copy_unavailable", tool,
                "this project's mirror is " + state.name().toLowerCase(),
                null, null,
                state == RepoState.CLONING
                        ? "A copy is being fetched now and a later review will have it. Work "
                        + "from the diff and read_file for this one."
                        : "Work from the diff and read_file, and do not assume the absence of "
                        + "callers means there are none.");
    }

    public static Refusal staleHandle(String tool) {
        return new Refusal("stale_handle", tool,
                "that handle belongs to an earlier review and its results are gone", null, null,
                "Call the tool again without a handle to start over.");
    }
}
