package ro.bitboy.mrr.forge;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One conformance suite, three fixture sets.
 *
 * <p>Every adapter is asked the same questions in mrr's vocabulary and has to answer the same
 * way, from whatever shape its host really sends. The fixtures are the hosts' own response
 * shapes; the assertions are entirely about {@link Forge}. That is the point — if an adapter
 * cannot satisfy an operation, that is a {@link ForgeCapabilities} flag and a named degradation,
 * not a skipped test, so there is nothing here for a subclass to opt out of.
 *
 * <p>Each subclass supplies its adapter, the repository id its host addresses by, and a routing
 * table from the paths that host really serves to the JSON it really returns.
 */
abstract class ForgeConformance {

    protected MockWebServer host;
    private Forge forge;
    private Project project;

    /** Path (without query) to response body. A path may be answered more than once. */
    protected abstract Map<String, String> routes();

    protected abstract Forge adapter(RestClient.Builder builder);

    /** What this host wants in {@code gitlabProjectId}. */
    protected abstract String repoId();

    /** Requests the suite saw, in order — for the assertions that care which call was made. */
    protected final List<RecordedRequest> seen = new ArrayList<>();

    @BeforeEach
    void startHost() throws Exception {
        host = new MockWebServer();
        Map<String, String> routes = new LinkedHashMap<>(routes());
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                String path = request.getPath() == null ? "" : request.getPath();
                int q = path.indexOf('?');
                String bare = q < 0 ? path : path.substring(0, q);
                String body = routes.get(bare);
                if (body == null) {
                    // A 404 rather than a hang: an adapter asking for something the fixture set
                    // does not describe should fail loudly in the test that needs it.
                    return new MockResponse().setResponseCode(404).setBody("{}");
                }
                return new MockResponse()
                        .setHeader("Content-Type", body.startsWith("{") || body.startsWith("[")
                                ? "application/json" : "text/plain")
                        .setBody(body);
            }
        });
        host.start();
        forge = adapter(RestClient.builder());
        project = project();
    }

    @AfterEach
    void stopHost() throws Exception {
        host.shutdown();
    }

    protected Forge forge() {
        return forge;
    }

    protected Project project() {
        if (project != null) {
            return project;
        }
        LlmProvider llm = new LlmProvider("l1", "u1", "entry", Project.Provider.OPENAI_COMPAT,
                "m", null, "k", Instant.now());
        return new Project("p1", "u1", "test", true, adapterKind(),
                host.url("/").toString().replaceAll("/+$", ""), repoId(),
                "tok", null, "webhook-secret", llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now());
    }

    private ForgeKind adapterKind() {
        return adapter(RestClient.builder()).kind();
    }

    // ── the questions every host has to answer ───────────────────────────────────────

    @Test
    void theDiffComesBackAsPerFilePatchesInMrrsOwnShape() {
        Diff diff = forge().changes(project(), 7);

        assertThat(diff.files()).containsExactly("src/A.java", "src/B.java");
        assertThat(diff.patches()).extracting(FilePatch::path)
                .containsExactly("src/A.java", "src/B.java");
        assertThat(diff.patches().getFirst().diff()).contains("+int x;");
        // The assembled blob is what the reviewer packs slices from, in mrr's own format.
        assertThat(diff.text()).startsWith("--- src/A.java\n");
        assertThat(diff.sourceBranch()).isEqualTo("fix/x");
        assertThat(diff.targetBranch()).isEqualTo("main");
    }

    @Test
    void anOpenRequestIsListedWithItsNumberAndHeadSha() {
        List<MergeRequest> open = forge().openMergeRequests(project());

        assertThat(open).hasSize(1);
        MergeRequest mr = open.getFirst();
        assertThat(mr.iid()).isEqualTo(7);
        assertThat(mr.sha()).isEqualTo("headsha1");
        assertThat(mr.draft()).isFalse();
        // Every host has its own word for "still open"; mrr's is `opened`.
        assertThat(mr.state()).isEqualTo("opened");
    }

    @Test
    void aMergedRequestReadsAsMergedHoweverTheHostSpellsIt() {
        Optional<MergeRequest> mr = forge().mergeRequest(project(), 9);

        assertThat(mr).isPresent();
        assertThat(mr.get().state()).isEqualTo("merged");
    }

    @Test
    void openingAThreadReturnsSomethingThatCanBeEditedLater() {
        Discussion thread = forge().postDiscussion(project(), 7, "### mrr review");

        assertThat(thread.discussionId()).isNotBlank();
        assertThat(thread.noteId()).isNotBlank();
    }

    @Test
    void aMissingRepositoryIsEmptyRatherThanAnException() {
        // 404 on a tree read is "empty repo", not a failure — every adapter has to agree,
        // because the librarian's first run happens before anyone has pushed.
        assertThat(forge().tree(project(), "no-such-branch", null, 10)).isEmpty();
        assertThat(forge().commitSha(project(), "no-such-branch")).isEmpty();
    }

    @Test
    void aBlankRefReadsAsNothingWithoutCallingTheHost() {
        int before = seen.size();

        assertThat(forge().tree(project(), "", null, 10)).isEmpty();
        assertThat(forge().commitSha(project(), null)).isEmpty();
        assertThat(forge().compare(project(), null, "b", 100)).isEmpty();

        assertThat(seen).hasSize(before);
    }

    @Test
    void capabilitiesNeverClaimSomethingTheAdapterCannotDo() {
        ForgeCapabilities caps = forge().capabilities(project());

        // Whatever else varies, a host mrr supports at all can open a thread and be read back.
        assertThat(caps).isNotNull();
        assertThat(caps.unknown()).allSatisfy(flag ->
                assertThat(flag).isIn("resolvableThreads", "threadedReplies", "unapprove"));
    }

    @Test
    void aDeliveryWithTheWrongSecretIsRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Gitlab-Token", "wrong");
        headers.add("X-Hub-Signature-256", "sha256=deadbeef");
        headers.add("X-Forgejo-Signature", "deadbeef");

        assertThat(forge().verifyWebhook(project(), headers, "{}")).isFalse();
    }

    @Test
    void anAnchorFromAnotherForgeIsRefusedRatherThanMisread() {
        // AnchorRef is opaque on purpose. An adapter handed one it did not mint must fall back
        // to an unanchored thread, not reach inside it.
        Optional<Discussion> posted = forge().postInlineDiscussion(
                project(), 7, new ForeignAnchor(), "src/A.java", 1, "body");

        assertThat(posted).isEmpty();
    }

    private record ForeignAnchor() implements AnchorRef {
    }
}
