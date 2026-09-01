package ro.bitboy.mrr.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the contract that stops a tool answer swallowing the window, and the half that
 * stops the model believing it saw everything.
 */
class BudgetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Envelope<Reference> envelopeOf(int count) {
        List<Reference> refs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            refs.add(new Reference("src/File" + i + ".java", i + 1, null, "call",
                    "Class" + i + ".method", "        service.parseToken(token);  // line " + i,
                    10 - i * 0.01, List.of("same_module"), "ast_filtered"));
        }
        return Envelope.full(refs, Tier.AST_FILTERED, "abc123");
    }

    @Test
    void aFullAnswerSaysSoAndKeepsEverything() {
        String json = Budget.render(mapper, envelopeOf(3), 100_000);

        assertThat(json).contains("\"total\":3").contains("\"returned\":3")
                .contains("\"degraded\":\"full\"");
    }

    @Test
    void aSampleReportsTheTotalItDidNotReturn() {
        // The single most valuable field here: eight of three hundred must never read as
        // eight of eight, because that turns a gap into false confidence.
        Envelope<Reference> sample = Envelope.sample(envelopeOf(8).results(), 312,
                Tier.AST_FILTERED, Degradation.RANKED_SAMPLE, "cap", "fr_abc", "abc123", null);

        String json = Budget.render(mapper, sample, 100_000);

        assertThat(json).contains("\"total\":312").contains("\"returned\":8")
                .contains("\"handle\":\"fr_abc\"").contains("\"truncationReason\":\"cap\"");
    }

    @Test
    void theLadderDropsContextBeforeItDropsResults() {
        // One line of code per hit is usually enough to judge relevance, so breadth is worth
        // more than detail when something has to go.
        Envelope<Reference> full = envelopeOf(20);
        java.util.function.UnaryOperator<Envelope<Reference>> dropContext =
                e -> new Envelope<>(e.results().stream().map(Reference::withoutContext).toList(),
                        e.total(), e.results().size(), e.tier(),
                        Degradation.NO_CONTEXT_LINES.wire(), e.handle(), "call_budget",
                        e.notes(), e.ref());
        // A budget that the full answer misses and the context-free one clears, so the test
        // pins which rung was taken rather than how big these particular strings happen to be.
        int budget = Budget.write(mapper, dropContext.apply(full)).length() + 10;
        assertThat(Budget.write(mapper, full).length()).isGreaterThan(budget);

        String json = Budget.render(mapper, full, budget, dropContext);

        assertThat(json).contains("\"degraded\":\"no_context_lines\"");
        assertThat(json).doesNotContain("// line 3");
        assertThat(json).contains("\"total\":20");
    }

    @Test
    void whenEveryRungOverflowsTheCountStillGetsThrough() {
        // A number the model can read beats a truncated document it cannot parse.
        String json = Budget.render(mapper, envelopeOf(50), 40);

        assertThat(json).contains("\"degraded\":\"count_only\"");
        assertThat(json).contains("\"total\":50");
        assertThat(json).contains("\"returned\":0");
    }

    @Test
    void aMixedResultSetReportsTheWeakerTier() {
        assertThat(Tier.AST_FILTERED.min(Tier.TEXTUAL)).isEqualTo(Tier.TEXTUAL);
        assertThat(Tier.TEXTUAL.min(Tier.AST_FILTERED)).isEqualTo(Tier.TEXTUAL);
        assertThat(Tier.AST_FILTERED.min(Tier.RESOLVED)).isEqualTo(Tier.AST_FILTERED);
        assertThat(Tier.AST_FILTERED.min(null)).isEqualTo(Tier.AST_FILTERED);
    }
}
