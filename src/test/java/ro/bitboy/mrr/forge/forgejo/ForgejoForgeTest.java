package ro.bitboy.mrr.forge.forgejo;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.bitboy.mrr.web.TestRestClients;
import ro.bitboy.mrr.forge.AnchorRef;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Forgejo's inline-comment path has the same two-step shape GitHub's does — create the review,
 * then read its comment id back — and inherited the same bug: a 5xx or timeout on either call
 * used to be read as "the position was rejected," which could duplicate a finding while
 * orphaning a review the host had already created. See {@code GitHubForgeTest} for the fuller
 * account; this covers the Forgejo adapter's own copy of the fix.
 */
class ForgejoForgeTest {

    private static final String ROOT = "/api/v1/repos/owner/repo";

    private MockWebServer host;
    private ForgejoForge forge;

    @BeforeEach
    void setUp() throws Exception {
        host = new MockWebServer();
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.equals(ROOT + "/pulls/7")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""
                                    {"number":7,"head":{"sha":"headsha1"},"base":{"ref":"main"}}""");
                }
                return new MockResponse().setResponseCode(404).setBody("{}");
            }
        });
        host.start();
        forge = new ForgejoForge(TestRestClients.builder());
    }

    @AfterEach
    void tearDown() throws Exception {
        host.shutdown();
    }

    @Test
    void aRejectedInlinePositionFallsBackToAnUnanchoredThread() {
        AnchorRef anchor = forge.diffRefs(project(), 7).orElseThrow();
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // Forgejo's own way of saying the line is not part of the diff. Routine.
                return new MockResponse().setResponseCode(422).setBody("{}");
            }
        });

        Optional<Discussion> posted = forge.postInlineDiscussion(
                project(), 7, anchor, "A.java", 3, "## finding");

        assertThat(posted).isEmpty();
    }

    /**
     * Regression: Forgejo commits the review (id 501 exists on the pull request), and only the
     * follow-up read of its comment id fails. That must propagate — falling back here would
     * post a duplicate unanchored finding while the review Forgejo already created sits there
     * with no id mrr ever recorded.
     */
    @Test
    void aFailureReadingBackAnAcceptedReviewPropagatesRatherThanDuplicatingTheFinding() {
        AnchorRef anchor = forge.diffRefs(project(), 7).orElseThrow();
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.equals(ROOT + "/pulls/7/reviews")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":501}");
                }
                if (path.equals(ROOT + "/pulls/7/reviews/501/comments")) {
                    return new MockResponse().setResponseCode(503).setBody("upstream down");
                }
                return new MockResponse().setResponseCode(404).setBody("{}");
            }
        });

        assertThatThrownBy(() -> forge.postInlineDiscussion(
                project(), 7, anchor, "A.java", 3, "## finding"))
                .isInstanceOf(ApiException.class);
    }

    /**
     * A 404 on {@code .diff} is a genuine format gap — some instance without that endpoint —
     * and the documented reason {@code filesOnly} exists at all.
     */
    @Test
    void aMissing404DiffEndpointFallsBackToTheFileList() {
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
                if (bare.equals(ROOT + "/pulls/7")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""
                                    {"number":7,"title":"t","head":{"ref":"fix/x","sha":"h"},
                                     "base":{"ref":"main"},"user":{"login":"ada"}}""");
                }
                if (bare.equals(ROOT + "/pulls/7.diff")) {
                    return new MockResponse().setResponseCode(404).setBody("not found");
                }
                if (bare.equals(ROOT + "/pulls/7/files")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""
                                    [{"filename":"A.java","patch":"@@\\n+int x;"}]""");
                }
                return new MockResponse().setResponseCode(404).setBody("{}");
            }
        });

        Diff diff = forge.changes(project(), 7, 10_000);

        assertThat(diff.files()).containsExactly("A.java");
        assertThat(diff.text()).contains("+int x;");
    }

    /**
     * Regression: a 5xx or a timeout from {@code .diff} used to be caught by the same
     * {@code catch (Exception)} as a missing endpoint, so the reviewer received
     * {@code filesOnly}'s placeholder patch text ("[patch body not returned by this host]")
     * and could post a verdict without ever seeing the real diff. Only a 404 may read as
     * "unsupported"; a real outage must fail the review instead.
     */
    @Test
    void aDiffOutageFailsClosedRatherThanFallingBackToPlaceholderPatches() {
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.equals(ROOT + "/pulls/7")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""
                                    {"number":7,"title":"t","head":{"ref":"fix/x","sha":"h"},
                                     "base":{"ref":"main"},"user":{"login":"ada"}}""");
                }
                if (path.equals(ROOT + "/pulls/7.diff")) {
                    return new MockResponse().setResponseCode(503).setBody("upstream down");
                }
                // filesOnly must never be reached — this test fails loudly if it is called.
                return new MockResponse().setResponseCode(500).setBody("should not be called");
            }
        });

        assertThatThrownBy(() -> forge.changes(project(), 7, 10_000))
                .isInstanceOf(ApiException.class);
    }

    private Project project() {
        LlmProvider llm = new LlmProvider("l1", "u1", "e", Project.Provider.OPENAI_COMPAT,
                "m", null, "k", Instant.now());
        return new Project("p1", "u1", "test", true, ForgeKind.FORGEJO,
                host.url("/").toString().replaceAll("/+$", ""), "owner/repo",
                "tok", null, "hook-secret", llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now());
    }
}
