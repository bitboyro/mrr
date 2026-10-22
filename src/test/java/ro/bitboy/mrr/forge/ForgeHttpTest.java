package ro.bitboy.mrr.forge;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.web.TestRestClients;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code exchangeRejectable} counts as the host refusing a diff position, and what it
 * counts as a failure.
 *
 * <p>The distinction is the whole reason the method exists. A host that will not render a
 * comment on an unchanged line is answering the question that was asked; a host that will not
 * accept the token has not looked at the line at all, and reporting the second as the first
 * sends the reader to the diff instead of to the token.
 */
class ForgeHttpTest {

    private MockWebServer host;
    private TestForge forge;

    @BeforeEach
    void startHost() throws Exception {
        host = new MockWebServer();
        host.start();
        forge = new TestForge(TestRestClients.builder());
    }

    @AfterEach
    void stopHost() throws Exception {
        host.shutdown();
    }

    @Test
    void aRefusedPositionComesBackEmpty() {
        host.enqueue(new MockResponse().setResponseCode(422).setBody("{}"));

        assertThat(forge.post()).isEmpty();
    }

    @Test
    void aMissingResourceAlsoComesBackEmpty() {
        host.enqueue(new MockResponse().setResponseCode(404).setBody("{}"));

        assertThat(forge.post()).isEmpty();
    }

    /** GitHub answers 403 for a scope a fine-grained token or an App was never granted. */
    @Test
    void aForbiddenTokenIsAFailureNotARefusedPosition() {
        host.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));

        assertThatThrownBy(forge::post)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("not allowed");
    }

    @Test
    void anUnauthenticatedTokenIsAFailureToo() {
        host.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));

        assertThatThrownBy(forge::post)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("401");
    }

    @Test
    void anOutageStillFailsTheReview() {
        host.enqueue(new MockResponse().setResponseCode(502).setBody("{}"));

        assertThatThrownBy(forge::post)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("502");
    }

    // ── sliceLines ───────────────────────────────────────────────────────────────────

    @Test
    void aLineRangeReturnsExactlyThoseLines() {
        assertThat(ForgeHttp.sliceLines("a\nb\nc\nd", 2, 3)).isEqualTo("b\nc");
        assertThat(ForgeHttp.sliceLines("a\nb\nc\nd", 3, null)).isEqualTo("c\nd");
        assertThat(ForgeHttp.sliceLines("a\nb\nc\nd", null, 2)).isEqualTo("a\nb");
        assertThat(ForgeHttp.sliceLines("a\nb\nc\nd", null, null)).isEqualTo("a\nb\nc\nd");
    }

    /** An end past the last line is a clamp, not an error — the file simply stops. */
    @Test
    void anEndPastTheLastLineIsClamped() {
        assertThat(ForgeHttp.sliceLines("a\nb\nc", 2, 900)).isEqualTo("b\nc");
    }

    /**
     * The one answer that must never be the whole file. It used to be: asking for lines
     * 5000–5100 of a three-line file returned all three lines with nothing to say they were
     * not the lines asked for, and a finding would then be filed against a line that does not
     * exist.
     */
    @Test
    void aRangePastTheEndOfTheFileSaysSoRatherThanReturningTheFile() {
        assertThat(ForgeHttp.sliceLines("a\nb\nc", 5000, 5100))
                .startsWith("error:")
                .contains("3 line(s)")
                .doesNotContain("a\nb\nc");
    }

    @Test
    void anInvertedRangeSaysSoRatherThanReturningTheFile() {
        assertThat(ForgeHttp.sliceLines("a\nb\nc", 3, 1))
                .startsWith("error:")
                .doesNotContain("a\nb\nc");
    }

    /** The smallest adapter that can reach the shared plumbing. */
    private final class TestForge extends ForgeHttp {

        TestForge(RestClient.Builder builder) {
            super(builder);
        }

        Optional<JsonNode> post() {
            return exchangeRejectable(project(), HttpMethod.POST, "/discussions",
                    Map.of("body", "b"));
        }

        @Override
        protected String apiRoot(Project project) {
            return host.url("/").toString().replaceAll("/+$", "");
        }

        @Override
        protected Map<String, String> authHeaders(Project project) {
            return Map.of("PRIVATE-TOKEN", "tok");
        }

        @Override
        protected String label() {
            return "TestForge";
        }
    }

    private Project project() {
        LlmProvider llm = new LlmProvider("l1", "u1", "entry", Project.Provider.OPENAI_COMPAT,
                "m", null, "k", Instant.now());
        return new Project("p1", "u1", "test", true, ForgeKind.GITHUB,
                host.url("/").toString().replaceAll("/+$", ""), "owner/repo",
                "tok", null, "webhook-secret", llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now());
    }
}
