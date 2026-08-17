package ro.bitboy.mrr.gitlab;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ro.bitboy.mrr.model.Project;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabClientTest {

    private MockWebServer gitlab;
    private GitLabClient client;

    @BeforeEach
    void setUp() throws Exception {
        gitlab = new MockWebServer();
        gitlab.start();
        client = new GitLabClient(RestClient.builder());
    }

    @AfterEach
    void tearDown() throws Exception {
        gitlab.shutdown();
    }

    /**
     * Regression: {@code RestClient.uri(String)} treats its argument as a URI template and
     * re-encodes it, so an already-encoded {@code group%2Fname} became {@code group%252Fname}
     * and GitLab 404'd looking for a project literally called "group%2Fname".
     *
     * <p>Only reproduces with a path-style project id — a numeric id has no slash to mangle,
     * which is why it survived the first round of testing.
     */
    @Test
    void encodesAPathStyleProjectIdExactlyOnce() throws Exception {
        gitlab.enqueue(json("""
                {"title":"t","description":"","source_branch":"a","target_branch":"main",
                 "web_url":"http://x/mr/1","author":{"name":"Ada","username":"ada"},
                 "changes":[{"new_path":"A.java","diff":"@@ -1 +1 @@"}]}
                """));

        client.changes(project("bitboy.ro/test-repo"), 1, 10_000);

        RecordedRequest request = gitlab.takeRequest();
        assertThat(request.getPath())
                .isEqualTo("/api/v4/projects/bitboy.ro%2Ftest-repo/merge_requests/1/changes")
                .doesNotContain("%252F");
    }

    @Test
    void leavesANumericProjectIdAlone() throws Exception {
        gitlab.enqueue(json("""
                {"title":"t","description":"","source_branch":"a","target_branch":"main",
                 "changes":[]}
                """));

        client.changes(project("85416286"), 7, 10_000);

        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/85416286/merge_requests/7/changes");
    }

    @Test
    void sendsThePatAndReadsTheAuthorAndFileList() throws Exception {
        gitlab.enqueue(json("""
                {"title":"Guard against divide by zero","description":"d",
                 "source_branch":"fix/div","target_branch":"main",
                 "web_url":"https://gitlab.com/g/p/-/merge_requests/3",
                 "author":{"name":"Ada Lovelace","username":"ada"},
                 "changes":[{"new_path":"A.java","diff":"@@ a @@"},
                            {"new_path":"B.java","diff":"@@ b @@"}]}
                """));

        GitLabClient.Diff diff = client.changes(project("g/p"), 3, 10_000);

        assertThat(gitlab.takeRequest().getHeader("PRIVATE-TOKEN")).isEqualTo("glpat-test");
        assertThat(diff.authorName()).isEqualTo("Ada Lovelace");
        assertThat(diff.authorUsername()).isEqualTo("ada");
        assertThat(diff.webUrl()).isEqualTo("https://gitlab.com/g/p/-/merge_requests/3");
        assertThat(diff.files()).containsExactly("A.java", "B.java");
        assertThat(diff.text()).contains("--- A.java", "--- B.java");
        assertThat(diff.truncated()).isFalse();
    }

    @Test
    void truncatesAnOversizedDiffAndSaysSo() throws Exception {
        gitlab.enqueue(json("""
                {"title":"t","description":"","source_branch":"a","target_branch":"main",
                 "changes":[{"new_path":"Big.java","diff":"%s"}]}
                """.formatted("x".repeat(5_000))));

        GitLabClient.Diff diff = client.changes(project("g/p"), 1, 500);

        assertThat(diff.truncated()).isTrue();
        assertThat(diff.text()).endsWith("[diff truncated by mrr]");
        assertThat(diff.patches()).hasSize(1);
        assertThat(diff.patches().getFirst().diff()).hasSize(5_000);
    }

    /**
     * The review goes to /discussions, not /notes — it belongs in a resolvable thread, and
     * GitLab gates /notes behind a much broader token permission.
     */
    @Test
    void postsTheReviewAsADiscussionAndReturnsBothIds() throws Exception {
        gitlab.enqueue(json("{\"id\":\"abc123\",\"notes\":[{\"id\":9001}]}"));

        GitLabClient.Discussion posted = client.postDiscussion(project("g/p"), 4, "## review");

        RecordedRequest request = gitlab.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/4/discussions");
        assertThat(request.getBody().readUtf8()).contains("## review");
        assertThat(posted.discussionId()).isEqualTo("abc123");
        assertThat(posted.noteId()).isEqualTo("9001");
    }

    @Test
    void editsTheReviewThroughItsOwningDiscussion() throws Exception {
        gitlab.enqueue(json("{\"id\":9001}"));

        client.updateDiscussionNote(project("g/p"), 4, "abc123", "9001", "## amended");

        RecordedRequest request = gitlab.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/4/discussions/abc123/notes/9001");
        assertThat(request.getBody().readUtf8()).contains("## amended");
    }

    @Test
    void resolvesAndReopensADiscussion() throws Exception {
        gitlab.enqueue(json("{\"id\":\"abc123\"}"));
        gitlab.enqueue(json("{\"id\":\"abc123\"}"));

        client.setDiscussionResolved(project("g/p"), 4, "abc123", true);
        client.setDiscussionResolved(project("g/p"), 4, "abc123", false);

        RecordedRequest resolve = gitlab.takeRequest();
        assertThat(resolve.getMethod()).isEqualTo("PUT");
        assertThat(resolve.getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/4/discussions/abc123");
        assertThat(resolve.getBody().readUtf8()).contains("\"resolved\":true");

        RecordedRequest reopen = gitlab.takeRequest();
        assertThat(reopen.getBody().readUtf8()).contains("\"resolved\":false");
    }

    @Test
    void readsAFileAtACommitAndSlicesLines() throws Exception {
        gitlab.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("one\ntwo\nthree\nfour"));

        String text = client.fileAt(project("g/p"), "src/A.java", "abc123", 2, 3, 10_000);

        RecordedRequest request = gitlab.takeRequest();
        assertThat(request.getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/repository/files/src%2FA.java/raw?ref=abc123");
        assertThat(text).isEqualTo("two\nthree");
    }

    @Test
    void listsOpenMergeRequestsWithDraftAndFallbackHeadSha() throws Exception {
        gitlab.enqueue(json("""
                [{"iid":7,"title":"Fix npe","state":"opened","sha":"abc123",
                  "web_url":"http://x/7","draft":false},
                 {"iid":8,"title":"wip","state":"opened",
                  "diff_refs":{"head_sha":"def456"},"web_url":"http://x/8",
                  "work_in_progress":true}]
                """));

        var mrs = client.openMergeRequests(project("g/p"));

        assertThat(mrs).hasSize(2);
        assertThat(mrs.get(0).iid()).isEqualTo(7);
        assertThat(mrs.get(0).sha()).isEqualTo("abc123");
        assertThat(mrs.get(0).draft()).isFalse();
        assertThat(mrs.get(1).sha()).isEqualTo("def456");
        assertThat(mrs.get(1).draft()).isTrue();
        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests?state=opened&per_page=100&page=1");
    }

    @Test
    void followsOpenMergeRequestPages() throws Exception {
        gitlab.enqueue(json("""
                [{"iid":1,"title":"a","state":"opened","sha":"aaa","web_url":"http://x/1","draft":false}]
                """).setHeader("X-Next-Page", "2"));
        gitlab.enqueue(json("""
                [{"iid":2,"title":"b","state":"opened","sha":"bbb","web_url":"http://x/2","draft":false}]
                """));

        var mrs = client.openMergeRequests(project("g/p"));

        assertThat(mrs).extracting(GitLabClient.MergeRequest::iid).containsExactly(1L, 2L);
        assertThat(gitlab.takeRequest().getPath()).endsWith("page=1");
        assertThat(gitlab.takeRequest().getPath()).endsWith("page=2");
    }

    @Test
    void overflowedChangesCountAsTruncated() throws Exception {
        gitlab.enqueue(json("""
                {"title":"t","description":"","source_branch":"a","target_branch":"main",
                 "overflow":true,
                 "changes":[{"new_path":"A.java","diff":"@@ a @@"}]}
                """));

        GitLabClient.Diff diff = client.changes(project("g/p"), 1);

        assertThat(diff.truncated()).isTrue();
        assertThat(diff.text()).contains("[diff truncated by GitLab]");
    }

    @Test
    void readsASingleMergeRequestIncludingMergedState() throws Exception {
        gitlab.enqueue(json("""
                {"iid":15,"title":"Daily usage","state":"merged","sha":"f97650fc",
                 "web_url":"http://x/15","draft":false}
                """));

        var mr = client.mergeRequest(project("g/p"), 15).orElseThrow();

        assertThat(mr.iid()).isEqualTo(15);
        assertThat(mr.state()).isEqualTo("merged");
        assertThat(mr.sha()).isEqualTo("f97650fc");
        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/15");
    }

    @Test
    void emptyRepositoryTreeIsAnEmptyListNotAnError() throws Exception {
        gitlab.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"404\"}"));

        assertThat(client.tree(project("g/p"), "main", null, 50)).isEmpty();
        assertThat(gitlab.takeRequest().getPath()).contains("/repository/tree");
    }

    @Test
    void approvesAMergeRequest() throws Exception {
        gitlab.enqueue(json("{\"id\":1}"));

        client.approve(project("g/p"), 4);

        RecordedRequest request = gitlab.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/4/approve");
    }

    @Test
    void listsDiscussionsIncludingResolvedOverviews() throws Exception {
        gitlab.enqueue(json("""
                [
                  {"id":"ov-old","notes":[{"id":"1","body":"### mrr review\\n\\nNo findings.",
                    "resolvable":true,"resolved":true,"system":false}]},
                  {"id":"finding","notes":[{"id":"2","body":"**[Major]** missing test",
                    "resolvable":true,"resolved":false,"system":false}]}
                ]
                """));

        var threads = client.listDiscussions(project("g/p"), 18);

        assertThat(threads).hasSize(2);
        assertThat(threads.get(0).discussionId()).isEqualTo("ov-old");
        assertThat(threads.get(0).resolved()).isTrue();
        assertThat(threads.get(1).discussionId()).isEqualTo("finding");
        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/18/discussions?per_page=100&page=1");
    }

    @Test
    void listsLaterDiscussionPagesAndPrefersTheDiscussionResolvedFlag() throws Exception {
        gitlab.enqueue(json("""
                [{"id":"sys","notes":[{"id":"1","body":"resolved all threads",
                  "resolvable":false,"system":true}]}]
                """).setHeader("X-Next-Page", "2"));
        gitlab.enqueue(json("""
                [{"id":"ov-old","resolved":true,"resolvable":true,
                  "notes":[{"id":"2","body":"### mrr review\\n\\nNo findings.",
                    "resolvable":true,"system":false}]}]
                """));

        var threads = client.listDiscussions(project("g/p"), 19);

        assertThat(threads).hasSize(2);
        assertThat(threads.get(1).discussionId()).isEqualTo("ov-old");
        assertThat(threads.get(1).resolved()).isTrue();
        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/19/discussions?per_page=100&page=1");
        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/19/discussions?per_page=100&page=2");
    }

    @Test
    void gitMetaReadsCommitCountAssigneesAndReviewers() throws Exception {
        gitlab.enqueue(json("""
                {"iid":3,"title":"t","state":"opened","sha":"abc",
                 "web_url":"https://gitlab.com/g/p/-/merge_requests/3",
                 "assignees":[{"name":"Ada Lovelace","username":"ada"}],
                 "reviewers":[{"name":"Grace Hopper","username":"grace"},
                              {"username":"linus"}]}
                """));
        gitlab.enqueue(json("[{\"id\":\"c1\"}]").setHeader("X-Total", "7"));

        GitLabClient.GitMeta meta = client.gitMeta(project("g/p"), 3);

        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/3");
        assertThat(gitlab.takeRequest().getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/3/commits?per_page=1");
        assertThat(meta.commitCount()).isEqualTo(7);
        assertThat(meta.assignees()).containsExactly("Ada Lovelace (@ada)");
        assertThat(meta.reviewers()).containsExactly("Grace Hopper (@grace)", "@linus");
        assertThat(meta.assigneesText()).isEqualTo("Ada Lovelace (@ada)");
        assertThat(meta.reviewersText()).contains("Grace Hopper (@grace)", "@linus");
    }

    @Test
    void gitMetaFallsBackToPageSizeWhenXTotalIsMissing() throws Exception {
        gitlab.enqueue(json("{\"iid\":1,\"assignees\":[],\"reviewers\":[]}"));
        gitlab.enqueue(json("[{\"id\":\"a\"},{\"id\":\"b\"}]"));

        assertThat(client.gitMeta(project("g/p"), 1).commitCount()).isEqualTo(2);
    }

    private MockResponse json(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private Project project(String gitlabProjectId) {
        return new Project("p1", "u1", "test", true,
                gitlab.url("/").toString().replaceAll("/+$", ""), gitlabProjectId,
                "glpat-test", "secret", Project.Provider.OPENAI_COMPAT, null,
                "m", "k", null, false, 1, java.util.List.of(), false,
                false, null, null, null, null, null, null, Instant.now());
    }
}
