package ro.bitboy.mrr.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Renders an answer, shrinking it until it fits.
 *
 * <p>mrr counts characters rather than tokens, because it has no tokenizer for every model it
 * talks to and does not need one: roughly four characters to the token is close enough to
 * keep a tool result from swallowing the window that {@code DiffWindows} just packed.
 *
 * <p>Rendering two or three times to find the rung that fits is free next to a model call, so
 * the ladder is walked by measuring rather than by estimating.
 */
public final class Budget {

    /** Characters to the token, near enough for bounding a tool result. */
    public static final int CHARS_PER_TOKEN = 4;

    /** What one tool call may return in a standard review. */
    public static final int CALL_BUDGET_CHARS = 6_000;

    private Budget() {
    }

    /**
     * Serialises the envelope, walking the ladder until it fits the budget.
     *
     * <p>Each rung is a function that produces a smaller envelope from the full one. The rung
     * that is actually emitted names itself in {@code degraded}, so a reader can tell a
     * complete answer from a sample without counting.
     */
    @SafeVarargs
    public static <T> String render(ObjectMapper mapper, Envelope<T> full, int budgetChars,
                                    UnaryOperator<Envelope<T>>... rungs) {
        String rendered = write(mapper, full);
        if (rendered.length() <= budgetChars) {
            return rendered;
        }
        Envelope<T> current = full;
        for (UnaryOperator<Envelope<T>> rung : rungs) {
            current = rung.apply(current);
            rendered = write(mapper, current);
            if (rendered.length() <= budgetChars) {
                return rendered;
            }
        }
        // Every rung overflowed, which means even the bare count did. Emit it anyway: a count
        // the model can read beats a truncated JSON document it cannot parse.
        return write(mapper, new Envelope<T>(List.of(), current.total(), 0, current.tier(),
                Degradation.COUNT_ONLY.wire(), null, "call_budget", current.notes(),
                current.ref()));
    }

    public static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "error: could not render the result (" + e.getOriginalMessage() + ")";
        }
    }
}
