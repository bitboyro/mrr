package ro.bitboy.mrr.forge.github;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import ro.bitboy.mrr.web.TestRestClients;
import ro.bitboy.mrr.forge.AnchorRef;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.DiscussionView;
import ro.bitboy.mrr.forge.ForgeEvent;
import ro.bitboy.mrr.forge.Signatures;
import ro.bitboy.mrr.model.ForgeKind;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.web.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first and third hard problems, tested: GitHub cannot resolve a thread over REST, and it
 * has no unapprove at all.
 */
class GitHubForgeTest {

    private static final String ROOT = "/api/v3/repos/owner/repo";

    private MockWebServer host;
    private GitHubForge forge;
    private final List<RecordedRequest> seen = new ArrayList<>();
    private String graphqlResponse = """
            {"data":{"repository":{"pullRequest":{"reviewThreads":{
              "pageInfo":{"hasNextPage":false,"endCursor":null},
              "nodes":[{"id":"PRRT_abc","isResolved":false,
                        "resolvedBy":null,
                        "comments":{"nodes":[{"databaseId":555,"body":"**[Major]** leak"}]}}]}}}}}""";

    @BeforeEach
    void setUp() throws Exception {
        host = new MockWebServer();
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                String path = request.getPath() == null ? "" : request.getPath();
                String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
                String body = switch (bare) {
                    case "/api/graphql" -> graphqlResponse;
                    case ROOT + "/pulls/7" -> "{\"head\":{\"sha\":\"headsha1\"}}";
                    case ROOT + "/issues/7/comments" -> "[]";
                    case ROOT + "/pulls/7/reviews" -> """
                            [{"id":99,"state":"APPROVED","user":{"login":"mrr-bot"}}]""";
                    case ROOT + "/pulls/7/reviews/99/dismissals" -> "{\"id\":99}";
                    case "/api/v3/user" -> "{\"login\":\"mrr-bot\"}";
                    default -> null;
                };
                if (body == null) {
                    return new MockResponse().setResponseCode(404).setBody("{}");
                }
                return new MockResponse()
                        .setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        host.start();
        forge = new GitHubForge(TestRestClients.builder());
    }

    @AfterEach
    void tearDown() throws Exception {
        host.shutdown();
    }

    @Test
    void ghesIsRootedUnderApiV3AndGraphqlSitsBesideIt() {
        assertThat(GitHubForge.apiBase(project())).isEqualTo(base() + "/api/v3");
        assertThat(GitHubForge.apiBase(githubCom())).isEqualTo("https://api.github.com");
    }

    @Test
    void unapproveIsAdvertisedAsMissingBecauseADismissalIsADifferentAct() {
        assertThat(forge.capabilities(project()).unapprove()).isFalse();
        // Everything else GitHub does have, including resolve — over GraphQL.
        assertThat(forge.capabilities(project()).resolvableThreads()).isTrue();
        assertThat(forge.capabilities(project()).unknown()).isEmpty();
    }

    @Test
    void retractingAnApprovalDismissesMrrsOwnReviewWithTheReason() {
        forge.unapprove(project(), 7, "mrr reopened 2 finding(s) on abc12345");

        RecordedRequest dismissal = seen.stream()
                .filter(r -> r.getPath() != null && r.getPath().endsWith("/dismissals"))
                .findFirst().orElseThrow();
        assertThat(dismissal.getMethod()).isEqualTo("PUT");
        assertThat(dismissal.getBody().readUtf8())
                .contains("mrr reopened 2 finding(s)")
                .contains("DISMISS");
    }

    @Test
    void aRejectedInlinePositionFallsBackToAnUnanchoredThreadWithoutPostingAnything() {
        AnchorRef anchor = forge.diffRefs(project(), 7).orElseThrow();
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                // 422 is GitHub's own way of saying the line cannot carry a comment — an
                // unchanged line, a path outside the diff. Routine, not a failure.
                return new MockResponse().setResponseCode(422).setBody("{}");
            }
        });

        java.util.Optional<Discussion> posted = forge.postInlineDiscussion(
                project(), 7, anchor, "A.java", 3, "## finding");

        assertThat(posted).isEmpty();
    }

    /**
     * Regression: this exact sequence — GitHub accepts and creates the review, then the
     * follow-up read of its comment id fails — used to be caught by the same
     * {@code catch (ApiException)} as a rejected position. mrr would silently fall back to an
     * unanchored thread, duplicating the finding while the review GitHub actually created sat
     * on the pull request with no id mrr ever recorded — un-editable and un-resolvable forever.
     * The fix only reads a 4xx from the *first* call (creating the review) as a rejection;
     * anything that happens after GitHub has committed to a real review must propagate.
     */
    @Test
    void aFailureReadingBackAnAcceptedReviewPropagatesRatherThanDuplicatingTheFinding() {
        AnchorRef anchor = forge.diffRefs(project(), 7).orElseThrow();
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.equals(ROOT + "/pulls/7/reviews")) {
                    // GitHub committed the review — id 501 now exists on the pull request.
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

    @Test
    void threadStateIsReadThroughGraphqlBecauseRestDoesNotExposeIt() {
        List<DiscussionView> threads = forge.listDiscussions(project(), 7);

        assertThat(threads).hasSize(1);
        DiscussionView thread = threads.getFirst();
        // The id mrr keeps is the REST comment id, prefixed — that is the only id it ever had.
        assertThat(thread.discussionId()).isEqualTo("r555");
        assertThat(thread.resolvable()).isTrue();
        assertThat(thread.resolved()).isFalse();
        assertThat(seen).anySatisfy(r -> assertThat(r.getPath()).isEqualTo("/api/graphql"));
    }

    @Test
    void resolvingLooksUpTheThreadNodeIdAndMutatesIt() {
        forge.setDiscussionResolved(project(), 7, "r555", true);

        List<String> graphql = seen.stream()
                .filter(r -> "/api/graphql".equals(r.getPath()))
                .map(r -> r.getBody().readUtf8())
                .toList();
        assertThat(graphql).hasSize(2);
        assertThat(graphql.get(0)).contains("reviewThreads");
        assertThat(graphql.get(1)).contains("resolveReviewThread").contains("PRRT_abc");
    }

    @Test
    void anIssueCommentIsNotAThreadSoResolvingItIsANoOp() {
        int before = seen.size();

        forge.setDiscussionResolved(project(), 7, "c4242", true);

        assertThat(seen).hasSize(before);
    }

    @Test
    void aGraphqlErrorNamesThePermissionThatCausesIt() {
        graphqlResponse = """
                {"errors":[{"type":"FORBIDDEN","message":"Resource not accessible"}]}""";

        assertThatThrownBy(() -> forge.listDiscussions(project(), 7))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Contents: Read and write");
    }

    @Test
    void aSignedDeliveryVerifiesAndAnUnsignedOneDoesNot() {
        String body = "{\"action\":\"opened\"}";
        HttpHeaders signed = new HttpHeaders();
        signed.add("X-Hub-Signature-256",
                "sha256=" + Signatures.hmacSha256Hex("hook-secret", body));

        assertThat(forge.verifyWebhook(project(), signed, body)).isTrue();
        assertThat(forge.verifyWebhook(project(), new HttpHeaders(), body)).isFalse();
    }

    @Test
    void aSynchronizeIsAnUpdateAndAMergedCloseIsAMerge() {
        assertThat(parse("pull_request", """
                {"action":"synchronize","pull_request":{"number":7,"head":{"sha":"abc"}}}""")
                .action()).isEqualTo(ForgeEvent.Action.UPDATED);
        assertThat(parse("pull_request", """
                {"action":"closed","pull_request":{"number":7,"merged":true,"head":{"sha":"abc"}}}""")
                .action()).isEqualTo(ForgeEvent.Action.MERGED);
        assertThat(parse("pull_request", """
                {"action":"closed","pull_request":{"number":7,"merged":false,"head":{"sha":"abc"}}}""")
                .action()).isEqualTo(ForgeEvent.Action.CLOSED);
    }

    @Test
    void aReplyIsFiledUnderTheThreadItAnswersNotUnderItsOwnId() {
        ForgeEvent event = parse("pull_request_review_comment", """
                {"action":"created","pull_request":{"number":7},
                 "comment":{"id":777,"in_reply_to_id":555,"body":"why?",
                            "user":{"login":"ada"}}}""");

        assertThat(event.kind()).isEqualTo(ForgeEvent.Kind.NOTE);
        assertThat(event.note().discussionId()).isEqualTo("r555");
        assertThat(event.note().noteId()).isEqualTo("777");
        assertThat(event.note().onMergeRequest()).isTrue();
    }

    /**
     * A review comment is inherently on a line, and an issue comment never is. That is what
     * lets mrr tell "somebody filed a defect" from "somebody said something".
     */
    @Test
    void aReviewCommentCarriesWhereOnTheDiffItWasLeft() {
        ForgeEvent inline = parse("pull_request_review_comment", """
                {"action":"created","pull_request":{"number":7},
                 "comment":{"id":1,"path":"A.java","line":12,"body":"overruns",
                            "user":{"login":"ada"}}}""");
        ForgeEvent general = parse("issue_comment", """
                {"action":"created","issue":{"number":7,"pull_request":{}},
                 "comment":{"id":2,"body":"ship it","user":{"login":"ada"}}}""");

        assertThat(inline.note().inline()).isTrue();
        assertThat(inline.note().filePath()).isEqualTo("A.java");
        assertThat(inline.note().line()).isEqualTo(12);
        assertThat(general.note().inline()).isFalse();
    }

    /** After a force-push GitHub drops `line` and keeps `original_line`. */
    @Test
    void anOutdatedReviewCommentFallsBackToWhereItWasWritten() {
        ForgeEvent event = parse("pull_request_review_comment", """
                {"action":"created","pull_request":{"number":7},
                 "comment":{"id":1,"path":"A.java","original_line":9,"body":"overruns",
                            "user":{"login":"ada"}}}""");

        assertThat(event.note().line()).isEqualTo(9);
    }

    @Test
    void anIssueCommentOnAnIssueRatherThanAPullRequestIsNotOurs() {
        ForgeEvent event = parse("issue_comment", """
                {"action":"created","issue":{"number":3},
                 "comment":{"id":1,"body":"hi","user":{"login":"ada"}}}""");

        assertThat(event.note().onMergeRequest()).isFalse();
    }

    private ForgeEvent parse(String event, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-GitHub-Event", event);
        try {
            return forge.parseEvent(headers,
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String base() {
        return host.url("/").toString().replaceAll("/+$", "");
    }

    /**
     * The raw-file read must send exactly one Accept. {@code authHeaders} already asks for
     * {@code vnd.github+json}, and appending the raw type instead of replacing it sent both:
     * GitHub echoed the pair back as one comma-joined Content-Type, which is not a legal media
     * type, and read_file died while reading the response rather than while sending it.
     */
    @Test
    void readingARawFileAsksForOneMediaTypeRatherThanTwo() {
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                return new MockResponse()
                        .setHeader("Content-Type", "text/plain; charset=utf-8")
                        .setBody("line one\nline two\n");
            }
        });

        String body = forge.fileAt(project(), "src/Foo.java", "headsha1", null, null, 10_000);

        assertThat(body).isEqualTo("line one\nline two\n");
        RecordedRequest read = seen.stream()
                .filter(r -> r.getPath() != null && r.getPath().contains("/contents/"))
                .findFirst().orElseThrow();
        assertThat(read.getHeaders().values(HttpHeaders.ACCEPT))
                .containsExactly("application/vnd.github.raw");
    }

    /** A response mrr cannot parse is still an answer the model can read, not a lost turn. */
    @Test
    void anUnreadableResponseComesBackAsAnErrorLineRatherThanAnException() {
        host.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                seen.add(request);
                return new MockResponse()
                        .setHeader("Content-Type", "application/vnd.github+json,application/vnd.github.raw")
                        .setBody("whatever");
            }
        });

        assertThat(forge.fileAt(project(), "src/Foo.java", "headsha1", null, null, 10_000))
                .startsWith("error:")
                .contains("src/Foo.java");
    }

    private Project project() {
        return project(base());
    }

    private Project githubCom() {
        return project("https://github.com");
    }

    private Project project(String url) {
        LlmProvider llm = new LlmProvider("l1", "u1", "e", Project.Provider.OPENAI_COMPAT,
                "m", null, "k", Instant.now());
        return new Project("p1", "u1", "test", true, ForgeKind.GITHUB, url, "owner/repo",
                "tok", null, "hook-secret", llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now());
    }
}
