package ro.bitboy.mrr.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Logs page is CSS, not a Java renderer. These selectors are the overflow
 * contract: a long thrown exception must not stretch the grid.
 */
class LogsCssTest {

    @Test
    void logGridStaysBoundedWhenALineHasALongException() throws Exception {
        String css = Files.readString(Path.of("ui/app/globals.css"));

        assertThat(css).contains("grid-template-columns: 96px 56px 110px minmax(0, 1fr)");
        assertThat(block(css, ".log-view")).contains("overflow-x: hidden");
        assertThat(block(css, ".log-line")).contains("max-width: 100%", "min-width: 0");
        assertThat(block(css, ".log-row")).contains("min-width: 0");
        assertThat(block(css, ".log-msg")).contains("min-width: 0", "overflow-wrap: anywhere");
        assertThat(block(css, ".log-thrown"))
                .contains("overflow-x: auto", "overflow-wrap: anywhere")
                .doesNotContain("grid-column");
    }

    private static String block(String css, String selector) {
        var matcher = Pattern.compile(Pattern.quote(selector) + " \\{([^}]+)\\}").matcher(css);
        assertThat(matcher.find()).as(selector).isTrue();
        return matcher.group(1);
    }
}
