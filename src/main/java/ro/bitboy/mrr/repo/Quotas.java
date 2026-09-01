package ro.bitboy.mrr.repo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much searching one unit of review may do.
 *
 * <p><strong>One of these per diff slice</strong>, because a slice is already an independent
 * agent run with its own prompt and its own turn budget — that is mrr's natural unit. A quota
 * held per review would let one slice spend everything and starve the rest; a quota held per
 * call would bound size but not breadth, and breadth is what fills a context window.
 *
 * <p>Budgets bound how big an answer is. Quotas bound how many questions get asked. Turn count
 * already limits the loop, so this is the cheaper insurance against a model that batches
 * several searches into one turn rather than the primary bound.
 */
public final class Quotas {

    /** What a standard review may spend per slice. */
    public static final int FIND_REFERENCES_PER_UNIT = 4;
    public static final int CO_CHANGED_PER_UNIT = 2;

    private final Map<String, Integer> limits = new LinkedHashMap<>();
    private final Map<String, Integer> used = new LinkedHashMap<>();
    private final List<String> refusals = new ArrayList<>();

    public static Quotas perUnit() {
        Quotas quotas = new Quotas();
        // symbol_at is unlimited: it is bounded by construction — one file, one line, one
        // definition — and it is the tool a review should lean on hardest.
        quotas.limits.put(RepoToolNames.FIND_REFERENCES, FIND_REFERENCES_PER_UNIT);
        quotas.limits.put(RepoToolNames.CO_CHANGED, CO_CHANGED_PER_UNIT);
        return quotas;
    }

    /** Records a call and says whether it was within budget. */
    public synchronized boolean claim(String tool) {
        Integer limit = limits.get(tool);
        if (limit == null) {
            return true;
        }
        int spent = used.getOrDefault(tool, 0);
        if (spent >= limit) {
            return false;
        }
        used.put(tool, spent + 1);
        return true;
    }

    public synchronized int limitFor(String tool) {
        return limits.getOrDefault(tool, Integer.MAX_VALUE);
    }

    public synchronized int usedFor(String tool) {
        return used.getOrDefault(tool, 0);
    }

    /** Noted so the review can say where it stopped looking, per the spec's own instruction. */
    public synchronized void recordRefusal(String description) {
        refusals.add(description);
    }

    public synchronized List<String> refusals() {
        return List.copyOf(refusals);
    }
}
