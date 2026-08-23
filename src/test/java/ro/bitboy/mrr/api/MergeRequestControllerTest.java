package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeGitLab;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MergeRequestControllerTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeGitLab gitlab;
    private FakeMemory memory;
    private MergeRequestController controller;
    private Principal alice;
    private Project project;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User user = store.createUser("alice", Role.USER);
        alice = new Principal(user.id(), user.name(), user.role());
        project = store.createProject(TestStores.project(user.id(), "calc", "secret"));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.CLEAN, null, "sha", 0);
        gitlab = new FakeGitLab();
        memory = new FakeMemory();
        controller = new MergeRequestController(store, gitlab, memory);
    }

    @Test
    void mergeRequiresTheOperatorToken() {
        assertThatThrownBy(() -> controller.merge(alice, project.id(), 7))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("bad_request"));
        assertThat(gitlab.merges).isZero();
    }

    @Test
    void mergeUsesTheOperatorTokenAndClosesTheCycle() {
        store.updateProject(withOperator(project, "glpat-operator"));
        project = store.findProject(project.id()).orElseThrow();

        GitLabClient.MergeRequest merged = controller.merge(alice, project.id(), 7);

        assertThat(merged.state()).isEqualTo("merged");
        assertThat(gitlab.merges).isEqualTo(1);
        assertThat(gitlab.lastToken).isEqualTo("glpat-operator");
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.MERGED);
        assertThat(memory.closed).containsExactly(project.id() + "!7:merge");
    }

    @Test
    void overallThreadUsesTheOperatorToken() {
        store.updateProject(withOperator(project, "glpat-operator"));

        GitLabClient.Discussion posted = controller.postThread(alice, project.id(), 7,
                new MergeRequestController.ThreadUpsert("Looks good."));

        assertThat(posted.discussionId()).isEqualTo("ov-1");
        assertThat(gitlab.posted).containsExactly("Looks good.");
        assertThat(gitlab.lastToken).isEqualTo("glpat-operator");
    }

    @Test
    void commitListUsesTheReviewToken() {
        gitlab.commits = List.of(new GitLabClient.MrCommit("abc", "msg", "Ada", "t", "msg"));

        var commits = controller.commits(alice, project.id(), 7);

        assertThat(commits).hasSize(1);
        assertThat(gitlab.lastToken).isEqualTo("glpat-x");
    }

    @Test
    void listingFilesCarriesNoPatchBodies() {
        gitlab.commitPatches = List.of(
                new GitLabClient.FilePatch("A.java", "@@\n+int x;"),
                new GitLabClient.FilePatch("B.java", "@@\n+int y;"));

        assertThat(controller.commitFiles(alice, project.id(), 7, "abc"))
                .containsExactly("A.java", "B.java");
        assertThat(gitlab.patchRequests).isEmpty();
    }

    @Test
    void unveilingAFileFetchesThatFileAlone() {
        gitlab.commitPatches = List.of(
                new GitLabClient.FilePatch("A.java", "@@\n+int x;"),
                new GitLabClient.FilePatch("B.java", "@@\n+int y;"));

        GitLabClient.FilePatch patch =
                controller.commitFilePatch(alice, project.id(), 7, "abc", "B.java");

        assertThat(patch.path()).isEqualTo("B.java");
        assertThat(patch.diff()).contains("+int y;").doesNotContain("+int x;");
        assertThat(gitlab.patchRequests).containsExactly("B.java");
    }

    @Test
    void unveilingAFileTheCommitDidNotTouchIs404() {
        gitlab.commitPatches = List.of(new GitLabClient.FilePatch("A.java", "@@\n+int x;"));

        assertThatThrownBy(() -> controller.commitFilePatch(alice, project.id(), 7, "abc", "Z.java"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("not_found"));
    }

    private static Project withOperator(Project p, String token) {
        return new Project(p.id(), p.ownerId(), p.name(), p.enabled(), p.gitlabUrl(),
                p.gitlabProjectId(), p.gitlabToken(), token, p.webhookSecret(), p.provider(),
                p.modelBaseUrl(), p.model(), p.modelKey(), p.skill(), p.skipDrafts(),
                p.agentMaxTurns(), p.agentTools(), p.autoApprove(), p.librarianEnabled(),
                p.memoryHeadSha(), p.memoryStatus(), p.memoryRefreshedAt(),
                p.lastErrorKind(), p.lastError(), p.lastErrorAt(), Instant.now());
    }
}
