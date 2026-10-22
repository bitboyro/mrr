package ro.bitboy.mrr.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.forge.ForgeFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenMrScanTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeForge gitlab;
    private FakeReviewer reviewer;
    private FakeMemory memory;
    private OpenMrScan scan;
    private Project project;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = TestStores.persist(store, TestStores.project(alice.id(), "calc", "hook-secret"));
        gitlab = new FakeForge();
        reviewer = new FakeReviewer();
        memory = new FakeMemory();
        scan = new OpenMrScan(store, ForgeFactory.fixed(gitlab), reviewer, memory, 120_000);
    }

    @Test
    void catchesAssignmentsMissedByWebhooksWithoutReviewingUnassignedRequests() {
        store.saveReviewPolicy(project.id(), new ro.bitboy.mrr.model.ReviewPolicy(
                ro.bitboy.mrr.model.ReviewPolicy.Mode.ASSIGNED, "mrr-bot", null));
        gitlab.openMrs = List.of(openMr(7, "sha-assignment"));
        assertThat(scan.scan(project).queued()).isZero();
        gitlab.assignedUsers.add("mrr-bot");
        assertThat(scan.scan(project).queued()).isEqualTo(1);
        store.markReady(store.findReview(project.id(), 7, "sha-assignment").orElseThrow().id(), "discussion", "note");
        assertThat(scan.scan(project).queued()).isZero();
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
    void autoReviewOffDoesNotQueue() {
        store.updateProject(project.withAutoReview(false));
        project = store.findProject(project.id()).orElseThrow();
        gitlab.openMrs = List.of(openMr(7, "sha-off"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.queued()).isEqualTo(0);
        assertThat(reviewer.submitted).isEmpty();
        assertThat(store.findReview(project.id(), 7, "sha-off")).isEmpty();
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
                new MergeRequest(9, "t", "merged", "sha-old", "https://gl/9", false));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.closed()).isEqualTo(1);
        assertThat(store.findMrStatus(project.id(), 9).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.MERGED);
        assertThat(memory.closed).containsExactly(project.id() + "!9:merge");
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void doesNotCloseAnOpenedMrMissingFromTheList() {
        String id = store.claimReview(project.id(), 11, "sha-live", "{}", TestStores.mrInfo())
                .review().id();
        store.markReady(id, "d", "n");
        store.upsertMrStatus(project.id(), 11, MrStatus.Status.NEEDS_WORK, id, "sha-live", 1);
        gitlab.openMrs = List.of();
        gitlab.oneMr = Optional.of(openMr(11, "sha-live"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.closed()).isEqualTo(0);
        assertThat(store.findMrStatus(project.id(), 11).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.NEEDS_WORK);
    }

    @Test
    void retriesARecentUnpostedRateLimitFailure() {
        String id = store.claimReview(project.id(), 7, "sha-rl", "{}", TestStores.mrInfo())
                .review().id();
        store.markFailed(id, FailureKind.RATE_LIMIT, "slow down");
        gitlab.openMrs = List.of(openMr(7, "sha-rl"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.queued()).isEqualTo(1);
        assertThat(reviewer.submitted).containsExactly(id);
        assertThat(store.findReview(id).orElseThrow().state()).isEqualTo(Review.State.PENDING);
    }

    @Test
    void doesNotRetryAPostedOrQuotaFailure() {
        String posted = store.claimReview(project.id(), 7, "sha-posted", "{}", TestStores.mrInfo())
                .review().id();
        store.markReady(posted, "d", "n");
        store.markFailed(posted, FailureKind.GITLAB, "later gitlab blip");
        String quota = store.claimReview(project.id(), 8, "sha-quota", "{}", TestStores.mrInfo())
                .review().id();
        store.markFailed(quota, FailureKind.QUOTA, "out of credits");
        gitlab.openMrs = List.of(openMr(7, "sha-posted"), openMr(8, "sha-quota"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.queued()).isEqualTo(0);
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void doesNotRetryAGitlabFailureAfterFindingsWerePosted() {
        String id = store.claimReview(project.id(), 7, "sha-partial", "{}", TestStores.mrInfo())
                .review().id();
        store.markFailed(id, FailureKind.GITLAB, "overview post failed");
        store.createFinding(new ro.bitboy.mrr.model.Finding(
                ro.bitboy.mrr.auth.Ids.newId(), id, project.id(), 7, "A.java", 1,
                ro.bitboy.mrr.model.Finding.Severity.MAJOR, "leak", "body",
                "disc-1", "note-1", true, ro.bitboy.mrr.model.Finding.State.OPEN,
                java.time.Instant.now(), null));
        gitlab.openMrs = List.of(openMr(7, "sha-partial"));

        OpenMrScan.ScanResult result = scan.scan(project);

        assertThat(result.queued()).isEqualTo(0);
        assertThat(reviewer.submitted).isEmpty();
        assertThat(store.findReview(id).orElseThrow().state()).isEqualTo(Review.State.FAILED);
    }

    @Test
    void skipsDraftsWhenTheProjectAsks() {
        Project skipping = TestStores.persist(store, 
                TestStores.project(project.ownerId(), "drafty", "other-secret", true, true));
        gitlab.openMrs = List.of(
                new MergeRequest(3, "wip", "opened", "sha-d", "https://gl/3", true));

        OpenMrScan.ScanResult result = scan.scan(skipping);

        assertThat(result.queued()).isEqualTo(0);
        assertThat(reviewer.submitted).isEmpty();
    }

    private static MergeRequest openMr(long iid, String sha) {
        return new MergeRequest(iid, "t", "opened", sha, "https://gl/" + iid, false);
    }
}
