package ro.bitboy.mrr.repo;

/**
 * How much of an answer survived its budget.
 *
 * <p>The ladder is walked in order, and whichever rung was actually emitted is reported. Note
 * that context lines are dropped before results are cut: one line of code per hit is usually
 * enough to judge relevance, so trading detail for breadth is the better exchange.
 */
public enum Degradation {

    FULL,
    NO_CONTEXT_LINES,
    RANKED_SAMPLE,
    GROUPED_COUNTS,
    COUNT_ONLY;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
