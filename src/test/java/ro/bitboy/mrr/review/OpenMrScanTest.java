package ro.bitboy.mrr.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeGitLab;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.gitlab.GitLabClient;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenMrScanTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeGitLab gitlab;
    private FakeReviewer reviewer;
    private FakeMemory memory;
    private OpenMrScan scan;
    private Project project;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = store.createProject(TestStores.project(alice.id(), "calc", "hook-secret"));
        gitlab = new FakeGitLab();
        reviewer = new FakeReviewer();
        memory = new FakeMemory();
        scan = new OpenMrScan(store, gitlab, reviewer, memory);
    }

    @Test
    void queuesANewHeadSha() {
        gitlab.openMrs = List.of(openMr(7, "sha-new"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.queued()).isEqualTo(1);
        Review review = store.findReview(project.id(), 7, "sha-new").orElseThrow();
        assertThat(reviewer.submitted).containsExactly(review.id());
    }

    @Test
    void doesNotSubmitATerminalReview() {
        String id = store.claimReview(project.id(), 7, "sha-done", "{}", TestStores.mrInfo())
                .review().id();
        store.markReady(id, "d", "n");
        gitlab.openMrs = List.of(openMr(7, "sha-done"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.queued()).isEqualTo(0);
        assertThat(reviewer.submitted).isEmpty();
        assertThat(reviewer.aligns).isEqualTo(1);
    }

    @Test
    void aFalseSubmitDoesNotCountAsQueued() {
        reviewer.accept = false;
        gitlab.openMrs = List.of(openMr(7, "sha-live"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.queued()).isEqualTo(0);
        assertThat(reviewer.submitted).hasSize(1);
    }

    @Test
    void marksACycleMergedWhenGitLabNoLongerListsIt() {
        String id = store.claimReview(project.id(), 9, "sha-old", "{}", TestStores.mrInfo())
                .review().id();
        store.markReady(id, "d", "n");
        store.upsertMrStatus(project.id(), 9, MrStatus.Status.NEEDS_WORK, id, "sha-old", 1);
        gitlab.openMrs = List.of();
        gitlab.oneMr = Optional.of(
                new GitLabClient.MergeRequest(9, "t", "merged", "sha-old", "https://gl/9", false));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.closed()).isEqualTo(1);
        assertThat(store.findMrStatus(project.id(), 9).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.MERGED);
        assertThat(memory.closed).containsExactly(project.id() + "!9:merge");
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void skipsDraftsWhenTheProjectAsks() {
        Project skipping = store.createProject(
                TestStores.project(project.ownerId(), "drafty", "other-secret", true, true));
        gitlab.openMrs = List.of(
                new GitLabClient.MergeRequest(3, "wip", "opened", "sha-d", "https://gl/3", true));

        OpenMrScan.ScanResult result = scan.scan(skipping);

        assertThat(result.queued()).isEqualTo(0);
        assertThat(reviewer.submitted).isEmpty();
    }

    private static GitLabClient.MergeRequest openMr(long iid, String sha) {
        return new GitLabClient.MergeRequest(iid, "t", "opened", sha, "https://gl/" + iid, false);
    }
}
