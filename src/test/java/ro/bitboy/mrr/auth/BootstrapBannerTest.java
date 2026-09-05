package ro.bitboy.mrr.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The box the root token is printed in.
 *
 * <p>Small, and worth pinning: it is the only time anybody ever sees this token, so it is
 * also the only chance to get a wrapped or ragged line wrong. The rows used to be built from
 * three unrelated expressions, and the bottom edge overhung the box by two columns on every
 * boot.
 */
class BootstrapBannerTest {

    @Test
    void everyRowIsTheSameWidth() {
        assertRectangular(Bootstrap.box("mrr_a3f9c2d1b4e5f60718293a4b5c6d7e8f"));
    }

    /** A token shorter than the title used to collapse the box rather than widen it. */
    @Test
    void aShortTokenStillGetsARectangle() {
        List<String> box = Bootstrap.box("mrr_short");

        assertRectangular(box);
        assertThat(box.get(1)).contains("mrr_short");
    }

    @Test
    void theTokenIsPrintedInFull() {
        String token = "mrr_" + "0123456789".repeat(6);

        List<String> box = Bootstrap.box(token);

        assertRectangular(box);
        assertThat(box.get(1)).contains(token);
    }

    private static void assertRectangular(List<String> box) {
        assertThat(box).hasSize(3);
        int width = box.getFirst().codePointCount(0, box.getFirst().length());
        assertThat(box).allSatisfy(row ->
                assertThat(row.codePointCount(0, row.length())).isEqualTo(width));
        assertThat(box.get(0)).startsWith("┌").endsWith("┐");
        assertThat(box.get(1)).startsWith("│").endsWith("│");
        assertThat(box.get(2)).startsWith("└").endsWith("┘");
    }
}
