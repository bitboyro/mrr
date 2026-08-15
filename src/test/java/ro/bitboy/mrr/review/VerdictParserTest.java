package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.ReviewModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Models wrap JSON in prose, fence it, and sometimes ignore the schema outright. None of that
 * may lose a review that was already paid for, so the parser is forgiving and the caller falls
 * back to posting raw text when it returns null.
 */
class VerdictParserTest {

    private final VerdictParser parser = new VerdictParser(new ObjectMapper());

    @Test
    void readsAWellFormedVerdict() {
        ReviewModel.Verdict v = parser.parse("""
                {"summary":"Adds batch reporting; several correctness problems.",
                 "findings":[
                   {"file":"A.java","line":43,"severity":"blocker",
                    "title":"Off-by-one overruns the array",
                    "body":"`i <= parts.length` throws on every row."},
                   {"file":"A.java","line":39,"severity":"major",
                    "title":"Reader never closed","body":"Leaks a descriptor per call."}],
                 "prior":[{"id":"f-1","status":"addressed","note":"now guarded"}]}
                """);

        assertThat(v.summary()).startsWith("Adds batch reporting");
        assertThat(v.findings()).hasSize(2);
        assertThat(v.findings().getFirst().severity()).isEqualTo(Finding.Severity.BLOCKER);
        assertThat(v.findings().getFirst().line()).isEqualTo(43);
        assertThat(v.priorVerdicts()).singleElement().satisfies(p -> {
            assertThat(p.findingId()).isEqualTo("f-1");
            assertThat(p.addressed()).isTrue();
        });
    }

    @Test
    void unwrapsAFencedBlock() {
        ReviewModel.Verdict v = parser.parse("""
                Here is my review:

                ```json
                {"summary":"Looks fine.","findings":[],"prior":[]}
                ```
                """);

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("Looks fine.");
        assertThat(v.findings()).isEmpty();
    }

    @Test
    void findsTheObjectWhenItIsBuriedInCommentary() {
        ReviewModel.Verdict v = parser.parse(
                "I reviewed the diff.\n{\"summary\":\"ok\",\"findings\":[]}\nHope that helps!");

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("ok");
    }

    /**
     * Review bodies are full of code, and code is full of braces. Counting depth naively
     * without tracking string state truncates the object at the first brace inside a snippet.
     */
    @Test
    void isNotFooledByBracesInsideStringValues() {
        ReviewModel.Verdict v = parser.parse("""
                {"summary":"s","findings":[{"file":"A.java","line":1,"severity":"minor",
                 "title":"t","body":"Replace `if (x) { y(); }` with a guard clause — note the } here."}]}
                """);

        assertThat(v).isNotNull();
        assertThat(v.findings()).singleElement()
                .satisfies(f -> assertThat(f.body()).contains("guard clause"));
    }

    @Test
    void returnsNullOnProseSoTheCallerCanFallBack() {
        assertThat(parser.parse("The change looks good to me overall.")).isNull();
        assertThat(parser.parse("")).isNull();
        assertThat(parser.parse(null)).isNull();
    }

    @Test
    void survivesAMalformedObject() {
        assertThat(parser.parse("{\"summary\": \"unterminated")).isNull();
    }

    /** Auto-resolving a live bug is far worse than leaving a thread open, so only an explicit
     *  "addressed" counts. */
    @Test
    void treatsAnythingButAddressedAsStillOpen() {
        ReviewModel.Verdict v = parser.parse("""
                {"summary":"s","findings":[],"prior":[
                  {"id":"a","status":"still_present"},
                  {"id":"b","status":"maybe"},
                  {"id":"c"},
                  {"id":"d","status":"ADDRESSED"}]}
                """);

        assertThat(v.priorVerdicts()).hasSize(4);
        assertThat(v.priorVerdicts().stream().filter(ReviewModel.PriorVerdict::addressed))
                .singleElement()
                .satisfies(p -> assertThat(p.findingId()).isEqualTo("d"));
    }

    @Test
    void dropsPriorVerdictsWithNoId() {
        ReviewModel.Verdict v = parser.parse(
                "{\"summary\":\"s\",\"prior\":[{\"status\":\"addressed\"},{\"id\":\"x\",\"status\":\"addressed\"}]}");

        assertThat(v.priorVerdicts()).singleElement()
                .satisfies(p -> assertThat(p.findingId()).isEqualTo("x"));
    }

    @Test
    void mapsUnknownSeveritiesRatherThanDroppingTheFinding() {
        ReviewModel.Verdict v = parser.parse("""
                {"findings":[
                  {"file":"A","severity":"critical","body":"b1"},
                  {"file":"B","severity":"medium","body":"b2"},
                  {"file":"C","severity":"nonsense","body":"b3"},
                  {"file":"D","body":"b4"}]}
                """);

        assertThat(v.findings()).extracting(ReviewModel.NewFinding::severity)
                .containsExactly(Finding.Severity.BLOCKER, Finding.Severity.MAJOR,
                        Finding.Severity.MINOR, Finding.Severity.MINOR);
    }

    @Test
    void derivesATitleWhenTheModelOmitsOne() {
        ReviewModel.Verdict v = parser.parse(
                "{\"findings\":[{\"file\":\"A\",\"body\":\"First line.\\nSecond line.\"}]}");

        assertThat(v.findings()).singleElement()
                .satisfies(f -> assertThat(f.title()).isEqualTo("First line."));
    }

    @Test
    void skipsEmptyFindingsAndTreatsAMissingLineAsAbsent() {
        ReviewModel.Verdict v = parser.parse(
                "{\"findings\":[{\"file\":\"A\"},{\"file\":\"B\",\"body\":\"real\"}]}");

        assertThat(v.findings()).singleElement().satisfies(f -> {
            assertThat(f.file()).isEqualTo("B");
            assertThat(f.line()).isNull();
        });
    }
}
