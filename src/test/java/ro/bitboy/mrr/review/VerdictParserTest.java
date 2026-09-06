package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.ReviewModel;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Models wrap JSON in prose, fence it, and sometimes ignore the schema outright. The parser
 * is forgiving; genuine failure returns null and the reviewer fail-closes.
 */
class VerdictParserTest {

    /**
     * Which concern raised a finding, self-reported in one pass. Not separate agents — saying
     * so in the ledger without running them would be claiming a review that did not happen.
     */
    @Test
    void aFindingCarriesTheConcernItCameFrom() {
        var verdict = new VerdictParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse("""
                        {"summary":"s","findings":[
                          {"file":"A.java","line":1,"severity":"major","title":"npe",
                           "body":"b","role":"Security"},
                          {"file":"B.java","line":2,"severity":"minor","title":"style",
                           "body":"b"}],
                         "prior":[]}""");

        assertThat(verdict.findings().get(0).role()).isEqualTo("security");
        assertThat(verdict.findings().get(1).role()).isNull();
    }

    /** Free text from a model, kept short enough to be a label rather than a paragraph. */
    @Test
    void anAbsurdlyLongRoleIsCutRatherThanStored() {
        var verdict = new VerdictParser(new com.fasterxml.jackson.databind.ObjectMapper())
                .parse("""
                        {"summary":"s","findings":[
                          {"file":"A.java","severity":"major","title":"t","body":"b",
                           "role":"%s"}],
                         "prior":[]}""".formatted("x".repeat(200)));

        assertThat(verdict.findings().getFirst().role()).hasSize(32);
    }

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
    void readsMemoryChangeAndImpact() {
        ReviewModel.Verdict v = parser.parse("""
                {"summary":"s","findings":[],"prior":[],
                 "memory":{"change":"Adds a gate.","impact":"Callers of Foo now must pass a token."}}
                """);
        assertThat(v.memory()).isNotNull();
        assertThat(v.memory().change()).isEqualTo("Adds a gate.");
        assertThat(v.memory().impact()).contains("Callers of Foo");
    }

    @Test
    void memoryIsOptionalSoOldVerdictsStillParse() {
        ReviewModel.Verdict v = parser.parse("{\"summary\":\"s\",\"findings\":[],\"prior\":[]}");
        assertThat(v.memory()).isNull();
    }

    @Test
    void completePriorsFillsMissingIdsAsStillPresent() {
        ReviewModel.Verdict v = parser.parse(
                "{\"summary\":\"s\",\"findings\":[],\"prior\":[{\"id\":\"a\",\"status\":\"addressed\"}]}");
        Finding openA = new Finding("a", "r", "p", 1, "A.java", 1, Finding.Severity.MINOR,
                "t", "b", "d", "n", false, Finding.State.OPEN, Instant.now(), null);
        Finding openB = new Finding("b", "r", "p", 1, "B.java", 1, Finding.Severity.MINOR,
                "t", "b", "d", "n", false, Finding.State.OPEN, Instant.now(), null);
        ReviewModel.Verdict completed = v.completePriors(List.of(openA, openB));
        assertThat(completed.priorVerdicts()).hasSize(2);
        assertThat(completed.priorVerdicts().get(0).addressed()).isTrue();
        assertThat(completed.priorVerdicts().get(1).findingId()).isEqualTo("b");
        assertThat(completed.priorVerdicts().get(1).addressed()).isFalse();
    }

    @Test
    void parseReplyReadsAddressedJsonAndLeavesProseOpen() {
        assertThat(parser.parseReply("{\"status\":\"addressed\",\"reply\":\"Fixed in HEAD.\"}"))
                .satisfies(r -> {
                    assertThat(r.addressed()).isTrue();
                    assertThat(r.reply()).isEqualTo("Fixed in HEAD.");
                });
        assertThat(parser.parseReply("The guard is still missing."))
                .satisfies(r -> {
                    assertThat(r.addressed()).isFalse();
                    assertThat(r.reply()).isEqualTo("The guard is still missing.");
                });
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

    /**
     * The failure this session was actually chasing. The final turn strips every tool, so the
     * model cannot really call one — but the shape of an attempt can still leak into the text
     * ahead of the answer, and the fragment is valid JSON entirely on its own. Grabbing the
     * first balanced object used to mean that fragment was all {@code parse} ever read: it
     * parsed cleanly, had neither {@code summary} nor {@code findings}, and the reviewer
     * fail-closed with "the model did not return a JSON verdict" — a real answer sitting
     * unread right after it.
     */
    @Test
    void aStrayToolCallShapedFragmentAheadOfTheVerdictDoesNotShadowIt() {
        ReviewModel.Verdict v = parser.parse("""
                {"path":"src/harness/cli.py","start_line":1100,"end_line":1210}
                { "summary": "Looks fine.", "findings": [], "prior": [] }
                """);

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("Looks fine.");
        assertThat(v.findings()).isEmpty();
    }

    /** Garbled text between two candidates is exactly the kind of noise this has to shrug off. */
    @Test
    void garbageBetweenTwoCandidatesDoesNotStopTheRealOneBeingFound() {
        ReviewModel.Verdict v = parser.parse(
                "{\"path\":\"A.java\",\"line\":1}\nрыстә\n"
                        + "{\"summary\":\"ok\",\"findings\":[]}");

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("ok");
    }

    /**
     * Three candidates rather than two, and the verdict-shaped one is in the middle rather
     * than last — the shape check has to be doing the choosing, not just "prefer the end".
     */
    @Test
    void theVerdictShapedCandidateWinsEvenWhenItIsNotTheLastOne() {
        ReviewModel.Verdict v = parser.parse("""
                {"path":"A.java","line":1}
                {"summary":"ok","findings":[]}
                {"unrelated":"trailing remark, also happens to be JSON"}
                """);

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("ok");
    }

    /** With no verdict-shaped candidate at all, the last one is still the better guess. */
    @Test
    void fallsBackToTheLastCandidateWhenNoneIsVerdictShaped() {
        assertThat(parser.parseReply(
                "{\"path\":\"A.java\",\"line\":1}\n{\"status\":\"addressed\",\"reply\":\"Fixed.\"}"))
                .satisfies(r -> {
                    assertThat(r.addressed()).isTrue();
                    assertThat(r.reply()).isEqualTo("Fixed.");
                });
    }

    /**
     * mrr's own review of this branch caught it: {@code parseReply} shared {@code parse}'s
     * "prefer the summary/findings-shaped candidate" rule, which is exactly the wrong shape
     * for a reply. A reply prompt carries the original finding as context, so an earlier
     * candidate that happens to look like a full verdict — echoed or quoted context, not the
     * model's answer — used to win over the genuine {@code {status, reply}} object sitting
     * later, and {@code parseReply} would then read the wrong candidate, fail its own
     * {@code status}/{@code reply} check, and silently fall back to treating the whole thing
     * as unaddressed prose.
     */
    @Test
    void aVerdictShapedCandidateAheadOfARealReplyDoesNotShadowIt() {
        VerdictParser.ReplyVerdict r = parser.parseReply("""
                Your finding was:
                {"summary":"leak","findings":[{"file":"A.java","body":"close it"}]}
                {"status":"addressed","reply":"Fixed in HEAD."}
                """);

        assertThat(r.addressed()).isTrue();
        assertThat(r.reply()).isEqualTo("Fixed in HEAD.");
    }

    /** The mirror image: a reply-shaped candidate ahead of a review must not shadow it either. */
    @Test
    void aReplyShapedCandidateAheadOfARealVerdictDoesNotShadowIt() {
        ReviewModel.Verdict v = parser.parse("""
                {"status":"addressed","reply":"unrelated echo"}
                {"summary":"ok","findings":[]}
                """);

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("ok");
    }

    /**
     * mrr's own review caught this one too: the fenced-block path used to be checked before
     * candidates were even collected, so a stray object the model happened to wrap in a
     * ```json fence — quoted context, an example — was trusted wholesale and returned before
     * {@code wantedShape} ever got a say, wrong shape or not. A fence buys a blob nothing now;
     * it is a candidate exactly like a bare one.
     */
    @Test
    void aFencedStrayObjectOfTheWrongShapeDoesNotShadowARealReplyEither() {
        VerdictParser.ReplyVerdict r = parser.parseReply("""
                Your finding was:
                ```json
                {"summary":"leak","findings":[{"file":"A.java","body":"close it"}]}
                ```
                {"status":"addressed","reply":"Fixed in HEAD."}
                """);

        assertThat(r.addressed()).isTrue();
        assertThat(r.reply()).isEqualTo("Fixed in HEAD.");
    }

    /** The mirror image, fenced: a fenced reply-shaped object must not shadow a real verdict. */
    @Test
    void aFencedReplyShapedObjectDoesNotShadowARealVerdictEither() {
        ReviewModel.Verdict v = parser.parse("""
                ```json
                {"status":"addressed","reply":"unrelated echo"}
                ```
                {"summary":"ok","findings":[]}
                """);

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("ok");
    }

    /** The plain fenced case still works with no special-casing behind it any more. */
    @Test
    void aFencedVerdictThatIsTheOnlyCandidateStillParses() {
        ReviewModel.Verdict v = parser.parse("""
                Here is my review:

                ```json
                {"summary":"Looks fine.","findings":[],"prior":[]}
                ```
                """);

        assertThat(v).isNotNull();
        assertThat(v.summary()).isEqualTo("Looks fine.");
    }
}
