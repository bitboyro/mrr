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
    void approvesAMergeRequest() throws Exception {
        gitlab.enqueue(json("{\"id\":1}"));

        client.approve(project("g/p"), 4);

        RecordedRequest request = gitlab.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath())
                .isEqualTo("/api/v4/projects/g%2Fp/merge_requests/4/approve");
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
                "m", "k", null, false, 1, java.util.List.of(), false, null, null, null, Instant.now());
    }
}
