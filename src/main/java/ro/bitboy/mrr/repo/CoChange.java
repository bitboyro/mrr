package ro.bitboy.mrr.repo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One file that historically moves with another.
 *
 * <p>Both ratios are returned because they answer different questions. {@code confidence} is
 * directional — of the commits that touched the file you asked about, what share also touched
 * this one — and reads naturally in a review comment. {@code jaccard} is symmetric and less
 * flattering to files that change constantly. Ranking uses neither directly; it uses the
 * age-decayed score, because coupling from three years ago may have been refactored away.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoChange(String file, int support, double confidence, double jaccard,
                       String lastCoChanged, double recencyWeightedScore) {
}
