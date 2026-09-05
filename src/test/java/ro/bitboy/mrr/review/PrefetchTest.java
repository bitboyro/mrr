package ro.bitboy.mrr.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a definition back out of the {@code symbol_at} answer.
 *
 * <p>Pre-fetch renders whole definitions into the prompt, so anything this gets wrong is code
 * the model reads as the repository's when it is not.
 */
class PrefetchTest {

    @Test
    void theOrdinaryEscapesComeBack() {
        assertThat(Prefetch.unescape("line one\\nline two")).isEqualTo("line one\nline two");
        assertThat(Prefetch.unescape("a\\tb")).isEqualTo("a\tb");
        assertThat(Prefetch.unescape("say \\\"hi\\\"")).isEqualTo("say \"hi\"");
        assertThat(Prefetch.unescape("nothing to do")).isEqualTo("nothing to do");
    }

    /**
     * The one chained {@code replace} calls cannot do. A definition containing the two
     * characters {@code \} and {@code n} — a Windows path, a regex, a Java string literal —
     * arrives as {@code \\n}, and unescaping {@code \n} first ate the backslash and left the
     * model reading a line break where the source has none.
     */
    @Test
    void anEscapedBackslashIsNotReadAsTheEscapeOfWhatFollowsIt() {
        assertThat(Prefetch.unescape("Pattern.compile(\\\"\\\\\\\\n\\\")"))
                .isEqualTo("Pattern.compile(\"\\\\n\")")
                .doesNotContain("\n");
        assertThat(Prefetch.unescape("C:\\\\\\\\temp")).isEqualTo("C:\\\\temp");
        assertThat(Prefetch.unescape("ends with a backslash \\\\")).isEqualTo(
                "ends with a backslash \\");
    }

    @Test
    void aUnicodeEscapeIsDecodedAndAnUnknownOneIsLeftAlone() {
        assertThat(Prefetch.unescape("caf\\u00e9")).isEqualTo("café");
        assertThat(Prefetch.unescape("\\q")).isEqualTo("\\q");
        assertThat(Prefetch.unescape("trailing \\")).isEqualTo("trailing \\");
    }
}
