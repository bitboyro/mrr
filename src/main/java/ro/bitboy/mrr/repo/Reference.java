package ro.bitboy.mrr.repo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One place a name is used.
 *
 * <p>{@code tier} is per reference rather than per answer. A repository with five parseable
 * languages and thirty unparseable ones produces mixed results routinely, and a single tier on
 * the envelope would let a byte match pass for a confirmed use. The envelope reports the floor;
 * this says which side of it each hit fell on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Reference(String file, int line, Integer column, String role,
                        String enclosingSymbol, String context, double rankScore,
                        List<String> rankReasons, String tier) {

    /** The same reference with its context line dropped — the first rung of the ladder. */
    public Reference withoutContext() {
        return new Reference(file, line, column, role, enclosingSymbol, null, rankScore,
                rankReasons, tier);
    }
}
