package ro.bitboy.mrr.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code symbol_at} sends back as a definition's body, and what it says it left out.
 *
 * <p>The elision marker is a claim about the file, so it has to be true: a model told "40
 * lines elided" will reason about a function of that size, and one told a negative number has
 * been handed something nobody can interpret.
 */
class SymbolBodyTest {

    private static Source source(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return Source.of("src/A.java", sb.toString());
    }

    private static Symbol symbol(int startLine, int endLine) {
        return new Symbol("src/A.java#A.f", "f", "A.f", "method", "src/A.java",
                startLine, endLine, "void f()", "public", "java");
    }

    @Test
    void aDefinitionInsideTheBudgetComesBackWhole() {
        RepoTools.Body body = RepoTools.body(source(30), symbol(1, 30), 200);

        assertThat(body.truncated()).isFalse();
        assertThat(body.text()).contains("line 1").contains("line 30")
                .doesNotContain("elided");
    }

    /**
     * The count has to be the number of lines actually left out. With the old fixed 40+10
     * window this said "150 lines elided" for every over-long definition regardless of the
     * budget the caller passed.
     */
    @Test
    void anOverlongDefinitionIsCutToTheBudgetItWasGiven() {
        RepoTools.Body body = RepoTools.body(source(300), symbol(1, 300), 100);

        assertThat(body.truncated()).isTrue();
        // 80 head + 20 tail = the 100 lines asked for, and the rest is accounted for.
        assertThat(body.text()).contains("… 200 lines elided …");
        assertThat(body.text()).contains("line 1").contains("line 80")
                .doesNotContain("line 81\n")
                .contains("line 281").contains("line 300");
    }

    /**
     * The bug this replaced: a budget below the old fixed window produced a negative count
     * and returned <em>more</em> lines than were asked for.
     */
    @Test
    void aSmallBudgetNeverReportsANegativeElision() {
        RepoTools.Body body = RepoTools.body(source(30), symbol(1, 30), 20);

        assertThat(body.truncated()).isTrue();
        assertThat(body.text()).contains("… 10 lines elided …")
                .doesNotContain("-");
    }

    /**
     * A budget so small that the one head line and the one tail line already cover the whole
     * definition. Sending it is right; announcing "0 lines elided" would not be.
     */
    @Test
    void aBudgetThatRoundsUpToTheWholeThingDoesNotClaimAnElision() {
        RepoTools.Body body = RepoTools.body(source(10), symbol(4, 5), 1);

        assertThat(body.truncated()).isFalse();
        assertThat(body.text()).doesNotContain("elided")
                .contains("line 4").contains("line 5");
    }

    /**
     * The property the whole budget exists for, over every value a model can send.
     *
     * <p>A budget the answer may exceed is not a budget. Below {@code MIN_BODY_LINES} a
     * truncated body cannot be rendered at all — it is a head and a tail — so the floor is
     * documented in the tool schema and clamped up to here; above it, head and tail add to
     * exactly the number asked for.
     */
    @Test
    void aTruncatedBodyNeverCarriesMoreSourceLinesThanWereAskedFor() {
        Source source = source(300);
        Symbol symbol = symbol(1, 300);

        for (int asked = -5; asked <= 120; asked++) {
            RepoTools.Body body = RepoTools.body(source, symbol, asked);
            long sourceLines = body.text().lines()
                    .filter(l -> !l.contains("lines elided")).count();
            int allowed = Math.max(RepoTools.MIN_BODY_LINES, asked);

            assertThat(sourceLines)
                    .describedAs("max_body_lines=%d", asked)
                    .isLessThanOrEqualTo(allowed);
        }
    }

    /**
     * The reviewer's finding on the first pass: zero — and any negative — forced head and tail
     * to one line each, so the reply carried two source lines and reported itself truncated
     * against a budget of nothing.
     */
    @Test
    void aNonPositiveBudgetIsRaisedToTheDocumentedFloorRatherThanIgnored() {
        Source source = source(300);
        Symbol symbol = symbol(1, 300);

        for (int asked : new int[]{0, -1, -1000}) {
            RepoTools.Body body = RepoTools.body(source, symbol, asked);

            assertThat(body.truncated()).describedAs("max_body_lines=%d", asked).isTrue();
            assertThat(body.text().lines().filter(l -> !l.contains("lines elided")).count())
                    .describedAs("max_body_lines=%d", asked)
                    .isEqualTo(RepoTools.MIN_BODY_LINES);
            assertThat(body.text()).contains("… 298 lines elided …");
        }
    }
}
