package ro.bitboy.mrr.repo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A capped answer that says what it left out.
 *
 * <p>{@code total} against {@code returned} is the single most valuable field here and the
 * reason the shape exists. A model that asks who calls a function, gets eight results and is
 * shown no count concludes "eight callers, all checked, safe to merge". Shown {@code total:
 * 312} it concludes the opposite. Capping an answer without admitting the cap is worse than
 * not answering, because it converts a gap into false confidence.
 *
 * <p>{@code tier} is the floor across the results, not the best of them. A repository with
 * five parseable languages and thirty unparseable ones routinely produces a mixed set, and
 * reporting the ceiling would let a textual guess pass for a confirmed reference.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope<T>(
        List<T> results,
        int total,
        int returned,
        String tier,
        String degraded,
        String handle,
        String truncationReason,
        List<String> notes,
        String ref) {

    public Envelope {
        results = results == null ? List.of() : List.copyOf(results);
        notes = notes == null || notes.isEmpty() ? null : List.copyOf(notes);
    }

    public static <T> Envelope<T> full(List<T> results, Tier tier, String ref) {
        return new Envelope<>(results, results.size(), results.size(), tier.wire(),
                Degradation.FULL.wire(), null, null, null, ref);
    }

    /** A sample of a larger set. {@code total} is the honest count before the cut. */
    public static <T> Envelope<T> sample(List<T> results, int total, Tier tier,
                                         Degradation degraded, String reason, String handle,
                                         String ref, List<String> notes) {
        return new Envelope<>(results, total, results.size(), tier.wire(), degraded.wire(),
                handle, reason, notes, ref);
    }

    public boolean truncated() {
        return returned < total;
    }
}
