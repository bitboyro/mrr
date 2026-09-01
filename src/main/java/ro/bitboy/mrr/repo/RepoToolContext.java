package ro.bitboy.mrr.repo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one unit of review may spend, and what it already knows.
 *
 * <p><strong>One per diff slice.</strong> A slice is already an independent agent run with its
 * own prompt and its own turn budget, so it is mrr's natural unit. A context held per review
 * would let one slice spend the whole allowance and starve the rest; one held per call would
 * bound size but not breadth.
 *
 * <p>The cursor store and the dedupe cache live here rather than in the database. A cursor is
 * meaningless outside the review whose revision it pins, and a persisted one would be a
 * stale-data bug waiting for the next force-push.
 */
public final class RepoToolContext {

    private final String reviewId;
    private final String headSha;
    /**
     * Every path this merge request touches.
     *
     * <p>Used two ways: as the strongest ranking signal in a reference search — which eight of
     * three hundred callers matter depends mostly on what the change touched — and to exclude
     * what the change already covers from a coupling lookup, since the interesting answer
     * there is what it did <em>not</em> touch.
     */
    private final Set<String> mrPaths;
    private final Quotas quotas;
    private final int callBudgetChars;

    private final Map<String, List<Reference>> cursors = new LinkedHashMap<>();

    public RepoToolContext(String reviewId, String headSha, Set<String> mrPaths,
                           Quotas quotas, int callBudgetChars) {
        this.reviewId = reviewId;
        this.headSha = headSha;
        this.mrPaths = mrPaths == null ? Set.of() : Set.copyOf(mrPaths);
        this.quotas = quotas;
        this.callBudgetChars = callBudgetChars;
    }

    public static RepoToolContext forSlice(String reviewId, String headSha,
                                           Set<String> mrPaths) {
        return new RepoToolContext(reviewId, headSha, mrPaths, Quotas.perUnit(),
                Budget.CALL_BUDGET_CHARS);
    }

    public String reviewId() {
        return reviewId;
    }

    public String headSha() {
        return headSha;
    }

    public Set<String> mrPaths() {
        return mrPaths;
    }

    public Quotas quotas() {
        return quotas;
    }

    public int callBudgetChars() {
        return callBudgetChars;
    }

    /** Stores a ranked list so a later page can be served without re-scanning. */
    public String openCursor(List<Reference> ranked) {
        String handle = "fr_" + Long.toHexString(System.nanoTime()) + Integer.toHexString(ranked.hashCode());
        cursors.put(handle, List.copyOf(ranked));
        return handle;
    }

    public List<Reference> cursor(String handle) {
        return cursors.get(handle);
    }
}
