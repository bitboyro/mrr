package ro.bitboy.mrr.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeGitLab;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ReplierTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeGitLab gitlab;
    private Project project;
    private ExecutorService workers;
    private RecordingModel model;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = store.createProject(TestStores.project(alice.id(), "calc", "hook-secret"));
        gitlab = new FakeGitLab();
        workers = Executors.newSingleThreadExecutor();
        String reviewId = store.claimReview(project.id(), 7, "sha", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 3, Finding.Severity.MAJOR, "leak", "close the reader",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void postsAReplyOnAnOwnedThread() {
        model = new RecordingModel(List.of(Result.of("Because the caller ignores the return.", "end_turn")));

        replier().reply(project.id(), 7, "disc-1", null, "nobody reads it", "ada");

        assertThat(store.listReplies(project.id(), 7)).singleElement()
                .extracting(r -> r.response())
                .isEqualTo("Because the caller ignores the return.");
        assertThat(gitlab.replies).containsExactly("disc-1:Because the caller ignores the return.");
    }

    @Test
    void aRefusalDoesNotTouchGitLab() {
        model = new RecordingModel(List.of(Result.refusal("refusal")));

        replier().reply(project.id(), 7, "disc-1", null, "why?", "ada");

        assertThat(store.listReplies(project.id(), 7)).isEmpty();
        assertThat(gitlab.replies).isEmpty();
    }

    @Test
    void aBlankModelAnswerDoesNotTouchGitLab() {
        model = new RecordingModel(List.of(Result.of("   ", "end_turn")));

        replier().reply(project.id(), 7, "disc-1", null, "why?", "ada");

        assertThat(store.listReplies(project.id(), 7)).isEmpty();
        assertThat(gitlab.replies).isEmpty();
    }

    @Test
    void aSecondDeliveryOfTheSameNoteDoesNotCallTheModelAgain() {
        model = new RecordingModel(List.of(
                Result.of("Because the caller ignores the return.", "end_turn"),
                Result.of("should not run", "end_turn")));
        Replier replier = replier();

        replier.reply(project.id(), 7, "disc-1", "note-99", "nobody reads it", "ada");
        replier.reply(project.id(), 7, "disc-1", "note-99", "nobody reads it", "ada");

        assertThat(store.listReplies(project.id(), 7)).hasSize(1);
        assertThat(gitlab.replies).hasSize(1);
        assertThat(model.turns).hasSize(1);
    }

    @Test
    void aFailedGitlabPostDoesNotRecordTheSourceNote() {
        gitlab.replyFailure = new RuntimeException("GitLab 502");
        model = new RecordingModel(List.of(Result.of("Because the caller ignores the return.", "end_turn")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                replier().reply(project.id(), 7, "disc-1", "note-99", "nobody reads it", "ada"))
                .hasMessageContaining("502");

        assertThat(store.listReplies(project.id(), 7)).isEmpty();
        assertThat(store.findReplyBySourceNote(project.id(), "disc-1", "note-99")).isEmpty();
        assertThat(gitlab.replies).isEmpty();
    }

    private Replier replier() {
        return new Replier(store, gitlab, TestStores.properties(), p -> model, workers);
    }
}
