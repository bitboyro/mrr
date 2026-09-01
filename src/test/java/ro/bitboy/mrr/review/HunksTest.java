package ro.bitboy.mrr.review;

import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.forge.FilePatch;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading where each hunk lands, from the headers the patch bodies already carry. */
class HunksTest {

    @Test
    void aHunkHeaderGivesItsPositionInTheNewFile() {
        FilePatch patch = new FilePatch("src/A.java", """
                @@ -10,3 +12,4 @@
                 unchanged
                -removed
                +added
                +also added
                """);

        List<Hunks.Hunk> hunks = Hunks.of(patch);

        assertThat(hunks).hasSize(1);
        Hunks.Hunk hunk = hunks.getFirst();
        assertThat(hunk.path()).isEqualTo("src/A.java");
        assertThat(hunk.newStart()).isEqualTo(12);
        assertThat(hunk.newCount()).isEqualTo(4);
        assertThat(hunk.removedLines()).isEqualTo(1);
        assertThat(hunk.addedLines()).isEqualTo(2);
    }

    @Test
    void aHeaderCarryingFunctionContextIsStillRead() {
        // git puts the enclosing declaration after the closing @@ by default, so this is the
        // common shape rather than an edge case. The pattern is unanchored at its end, and the
        // suffix lands in the body where it counts as neither an addition nor a removal.
        FilePatch patch = new FilePatch("src/A.java", """
                @@ -20,4 +20,5 @@ public void parse(String token)
                 unchanged
                -removed
                +added
                """);

        Hunks.Hunk hunk = Hunks.of(patch).getFirst();

        assertThat(hunk.newStart()).isEqualTo(20);
        assertThat(hunk.newCount()).isEqualTo(5);
        assertThat(hunk.removedLines()).isEqualTo(1);
        assertThat(hunk.addedLines()).isEqualTo(1);
    }

    @Test
    void aContextSuffixOnAOneLineHeaderIsAlsoRead() {
        FilePatch patch = new FilePatch("src/A.java", "@@ -5 +7 @@ class Foo\n-a\n+b\n");

        Hunks.Hunk hunk = Hunks.of(patch).getFirst();

        assertThat(hunk.newStart()).isEqualTo(7);
        assertThat(hunk.newCount()).isEqualTo(1);
    }

    @Test
    void severalHunksInOnePatchAreReadSeparately() {
        FilePatch patch = new FilePatch("src/A.java", """
                @@ -1,2 +1,2 @@
                -a
                +b
                @@ -20,2 +20,3 @@
                 keep
                +new
                """);

        assertThat(Hunks.of(patch)).extracting(Hunks.Hunk::newStart).containsExactly(1, 20);
    }

    @Test
    void aHeaderWithNoCountMeansOneLine() {
        FilePatch patch = new FilePatch("src/A.java", "@@ -5 +7 @@\n-a\n+b\n");

        Hunks.Hunk hunk = Hunks.of(patch).getFirst();

        assertThat(hunk.newStart()).isEqualTo(7);
        assertThat(hunk.newCount()).isEqualTo(1);
    }

    @Test
    void aPatchWithNoHunksIsEmptyRatherThanAnError() {
        assertThat(Hunks.of(new FilePatch("src/A.java", ""))).isEmpty();
        assertThat(Hunks.of(new FilePatch("src/A.java", null))).isEmpty();
        assertThat(Hunks.of(null)).isEmpty();
    }

    @Test
    void aBodyOnlyChangeIsDistinguishedFromASignatureChange() {
        // The filter that keeps reference searches from firing on every edit: a change inside
        // a body cannot break a caller, while a change to the declaration line can.
        Hunks.Hunk insideBody = new Hunks.Hunk("A.java", 20, 3, 1, 1);
        Hunks.Hunk overDeclaration = new Hunks.Hunk("A.java", 10, 4, 2, 2);
        Hunks.Hunk pureAddition = new Hunks.Hunk("A.java", 30, 2, 0, 2);

        assertThat(Prefetch.classify(insideBody, 10)).isEqualTo(Prefetch.Change.BODY_ONLY);
        assertThat(Prefetch.classify(overDeclaration, 10))
                .isEqualTo(Prefetch.Change.SIGNATURE_CHANGED);
        assertThat(Prefetch.classify(pureAddition, 30)).isEqualTo(Prefetch.Change.ADDED);
        assertThat(Prefetch.classify(insideBody, 10).worthSearchingFor()).isFalse();
        assertThat(Prefetch.classify(overDeclaration, 10).worthSearchingFor()).isTrue();
    }
}
