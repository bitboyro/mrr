package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeGitLab;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewerTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeGitLab gitlab;
    private FakeMemory memory;
    private Project project;
    private ExecutorService workers;
    private RecordingModel model;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = store.createProject(TestStores.project(alice.id(), "calc", "hook-secret"));
        gitlab = new FakeGitLab();
        memory = new FakeMemory();
        workers = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void aJsonVerdictIsReadyAndPosted() {
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"Looks safe\",\"findings\":[],\"prior\":[]}", "end_turn")));
        String id = claim("sha-ready");

        reviewer().run(id);

        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.READY);
        assertThat(review.errorKind()).isNull();
        assertThat(review.discussionId()).isEqualTo("ov-1");
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.CLEAN);
        assertThat(gitlab.posted).isNotEmpty();
        assertThat(memory.upserts).isEqualTo(1);
        assertThat(gitlab.resolved).containsExactly("ov-1:true");
    }

    @Test
    void alignGitlabDoesNotReResolveAnAlreadyResolvedOverview() {
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"Looks safe\",\"findings\":[],\"prior\":[]}", "end_turn")));
        String id = claim("sha-align");
        Reviewer reviewer = reviewer();
        reviewer.run(id);
        assertThat(gitlab.resolved).containsExactly("ov-1:true");

        reviewer.alignGitlab(project, 7);
        reviewer.alignGitlab(project, 7);

        assertThat(gitlab.resolved).containsExactly("ov-1:true");
    }

    @Test
    void alignGitlabDoesNotResolveAnOverviewMissingFromTheList() {
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"Looks safe\",\"findings\":[],\"prior\":[]}", "end_turn")));
        String id = claim("sha-missing");
        Reviewer reviewer = reviewer();
        reviewer.run(id);
        assertThat(gitlab.resolved).containsExactly("ov-1:true");

        gitlab.discussions.clear();
        reviewer.alignGitlab(project, 7);

        assertThat(gitlab.resolved).containsExactly("ov-1:true");
    }

    @Test
    void unparseableOutputFailsClosedAndKeepsTheResponse() {
        String prose = "Here is a nice review in markdown. Looks fine.";
        model = new RecordingModel(List.of(Result.of(prose, "end_turn")));
        String id = claim("sha-prose");

        reviewer().run(id);

        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.FAILED);
        assertThat(review.errorKind()).isEqualTo(FailureKind.PROVIDER);
        assertThat(review.response()).isEqualTo(prose);
        assertThat(review.discussionId()).isNull();
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.FAILED);
        assertThat(gitlab.posted).isEmpty();
        assertThat(gitlab.inlineAttempts).isEmpty();
        assertThat(memory.upserts).isEqualTo(0);
    }

    @Test
    void fencedJsonStillCountsAsAVerdict() {
        model = new RecordingModel(List.of(Result.of("""
                Sure.
                ```json
                {"summary":"ok","findings":[],"prior":[]}
                ```
                """, "end_turn")));
        String id = claim("sha-fence");

        reviewer().run(id);

        assertThat(store.findReview(id).orElseThrow().state()).isEqualTo(Review.State.READY);
        assertThat(gitlab.posted).isNotEmpty();
    }

    @Test
    void aRefusalFailsClosedWithoutSavingAResponse() {
        model = new RecordingModel(List.of(Result.refusal("refusal")));
        String id = claim("sha-refuse");

        reviewer().run(id);

        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.FAILED);
        assertThat(review.errorKind()).isEqualTo(FailureKind.REFUSAL);
        assertThat(review.response()).isNull();
        assertThat(gitlab.posted).isEmpty();
    }

    @Test
    void submitIsSingleFlightPerReviewId() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        model = new RecordingModel(() -> {
            entered.countDown();
            try {
                if (!hold.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("hold timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return Result.of("{\"summary\":\"ok\",\"findings\":[],\"prior\":[]}", "end_turn");
        });
        String id = claim("sha-dup");
        Reviewer reviewer = reviewer();

        assertThat(reviewer.submit(id)).isTrue();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(reviewer.submit(id)).isFalse();
        hold.countDown();
        workers.shutdown();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.findReview(id).orElseThrow().state()).isEqualTo(Review.State.READY);
    }

    private String claim(String sha) {
        return store.claimReview(project.id(), 7, sha, "{}", TestStores.mrInfo()).review().id();
    }

    private Reviewer reviewer() {
        return new Reviewer(store, gitlab, TestStores.properties(), new ObjectMapper(),
                memory, p -> model, workers);
    }
}
