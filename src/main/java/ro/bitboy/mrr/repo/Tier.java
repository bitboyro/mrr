package ro.bitboy.mrr.repo;

/**
 * How an answer was produced, so the model can tell a fact from a guess.
 *
 * <p>This exists because mrr's findings open threads that count against a merge gate. A
 * comment built on a textual match — which also matches a mention in a comment, a string
 * literal, or a different class's method of the same name — must read as a question rather
 * than an assertion, and the model can only know to do that if the downgrade is visible.
 *
 * <p>mrr never emits {@link #RESOLVED}. It would mean a compiler or a SCIP index, and mrr
 * runs neither: executing the repository's build to resolve dependencies is a trust boundary
 * the product does not cross. The constant exists so that ingesting a SCIP artifact produced
 * by the customer's own CI would not be a wire change, and every request for it is downgraded
 * with a note rather than silently answered at a lower tier.
 */
public enum Tier {

    /** A byte match. A candidate, not a reference. */
    TEXTUAL,

    /** Confirmed against a parsed syntax tree: a real identifier in a real expression. */
    AST_FILTERED,

    /** Compiler- or index-resolved. Never produced by mrr today. */
    RESOLVED;

    /** The weaker of two tiers — how an envelope reports a mixed result set. */
    public Tier min(Tier other) {
        if (other == null) {
            return this;
        }
        return compareTo(other) <= 0 ? this : other;
    }

    public String wire() {
        return name().toLowerCase();
    }
}
