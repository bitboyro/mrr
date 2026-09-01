package ro.bitboy.mrr.repo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The byte-versus-char rules, pinned.
 *
 * <p>These look pedantic until you see the failure they prevent: a finding posted against a
 * line that has nothing to do with the code it describes.
 */
class SourceTest {

    @Test
    void linesAreCountedInBytesNotChars() {
        // Two-byte characters above the code: the string index and the byte index diverge by
        // one per diacritic, and everything below inherits the drift.
        Source s = Source.of("a.rs", "// comentariu românesc\nfn g() {}\n");

        assertThat(s.byteLength()).isGreaterThan("// comentariu românesc\nfn g() {}\n".length());
        assertThat(s.lineCount()).isEqualTo(3);
        assertThat(s.lines(2, 2)).isEqualTo("fn g() {}");
        assertThat(s.lineOf(s.startOfLine(2))).isEqualTo(2);
    }

    @Test
    void aSpanDecodesToTheTextItActuallyCovers() {
        Source s = Source.of("a.rs", "// românesc\nfn g() {}\n");
        int start = s.startOfLine(2);

        // Slicing the underlying Java string with these offsets would return the wrong
        // characters; slicing the bytes returns the code.
        assertThat(s.slice(start, start + 2)).isEqualTo("fn");
    }

    @Test
    void lineLookupSurvivesOffsetsPastTheEnd() {
        Source s = Source.of("a.java", "class A {}\n");

        assertThat(s.lineOf(-5)).isEqualTo(1);
        assertThat(s.lineOf(9_999)).isEqualTo(s.lineCount());
        assertThat(s.lines(4, 9)).isEmpty();
    }

    @Test
    void theContentHashDependsOnTheBytesAndNothingElse() {
        // What the parse cache is keyed on. Same-length different-content is the case that
        // matters: anything derived from a ref plus a length cannot tell these apart.
        Source alpha = Source.of("A.java", "class A { void alpha() {} }");
        Source gamma = Source.of("A.java", "class A { void gamma() {} }");

        assertThat(alpha.byteLength()).isEqualTo(gamma.byteLength());
        assertThat(alpha.contentHash()).isNotEqualTo(gamma.contentHash());
        // Identical bytes hash the same whatever path they arrived under.
        assertThat(Source.of("other/A.java", "class A { void alpha() {} }").contentHash())
                .isEqualTo(alpha.contentHash());
        assertThat(alpha.contentHash()).isEqualTo(alpha.contentHash()).hasSize(32);
    }

    @Test
    void aNulByteMeansBinary() {
        assertThat(new Source("a.bin", new byte[]{1, 0, 2}).binary()).isTrue();
        assertThat(Source.of("a.java", "class A {}").binary()).isFalse();
    }

    @Test
    void crlfLineEndingsDoNotLeakIntoASlice() {
        Source s = new Source("a.java", "class A {\r\n  int x;\r\n}\r\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(s.lines(2, 2)).isEqualTo("  int x;");
    }
}
