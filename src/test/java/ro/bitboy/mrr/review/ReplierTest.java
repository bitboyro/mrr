package ro.bitboy.mrr.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.FindingEvent;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeFactory;

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
    private FakeForge gitlab;
    private Project project;
    private ExecutorService workers;
    private RecordingModel model;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = TestStores.persist(store, TestStores.project(alice.id(), "calc", "hook-secret"));
        gitlab = new FakeForge();
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
        assertThat(gitlab.resolved).isEmpty();
        assertThat(store.openFindings(project.id(), 7)).hasSize(1);
    }

    @Test
    void anAddressedReplyResolvesTheFindingThread() {
        model = new RecordingModel(List.of(Result.of(
                "{\"status\":\"addressed\",\"reply\":\"The reader is closed now.\"}", "end_turn")));

        replier().reply(project.id(), 7, "disc-1", null, "fixed in HEAD", "ada");

        assertThat(gitlab.replies).containsExactly("disc-1:The reader is closed now.");
        assertThat(gitlab.resolved).contains("disc-1:true");
        assertThat(store.openFindings(project.id(), 7)).isEmpty();
        // A reply closing a finding is the agent's judgement, not the human's — the developer
        // argued, the model agreed. The event says so, and points at the reply that did it.
        String findingId = store.findingsOfMr(project.id(), 7).getFirst().id();
        assertThat(store.eventsOf(findingId)).last().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.ADDRESSED);
            assertThat(e.actorKind()).isEqualTo(FindingEvent.ActorKind.AGENT);
            assertThat(e.replyId()).isEqualTo(store.listReplies(project.id(), 7).getFirst().id());
            assertThat(e.reviewId()).isNull();
        });
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

    @Test
    void anInFlightClaimIsLeftToTheOwner() {
        model = new RecordingModel(List.of(Result.of("second", "end_turn")));
        Reply claimed = new Reply(Ids.newId(), project.id(), 7, "disc-1", null, "note-99",
                "p", "first", null, Instant.now());
        assertThat(store.claimUnpostedReply(claimed)).isTrue();

        replier().reply(project.id(), 7, "disc-1", "note-99", "nobody reads it", "ada");

        assertThat(gitlab.replies).isEmpty();
        assertThat(store.sourceNotePosted(project.id(), "disc-1", "note-99")).isFalse();
        assertThat(model.turns).isEmpty();
    }

    @Test
    void aStaleUnpostedClaimCanBeTakenOver() {
        model = new RecordingModel(List.of(Result.of("recovered", "end_turn")));
        Reply claimed = new Reply(Ids.newId(), project.id(), 7, "disc-1", null, "note-99",
                "p", "old", null, Instant.now().minusSeconds(120));
        assertThat(store.claimUnpostedReply(claimed)).isTrue();

        replier().reply(project.id(), 7, "disc-1", "note-99", "nobody reads it", "ada");

        assertThat(gitlab.replies).containsExactly("disc-1:recovered");
        assertThat(store.sourceNotePosted(project.id(), "disc-1", "note-99")).isTrue();
    }

    @Test
    void aHostThatCannotResolveStillClosesTheFindingAndMarksTheComment() {
        // Internal state is never weakened by a host limitation: without this, a reply the
        // model agreed with would leave the finding OPEN forever on such a host.
        gitlab.capabilities = new ForgeCapabilities(true, false, true, true, false, true, true);
        model = new RecordingModel(List.of(Result.of(
                "{\"status\":\"addressed\",\"reply\":\"The reader is closed now.\"}", "end_turn")));

        replier().reply(project.id(), 7, "disc-1", null, "fixed in HEAD", "ada");

        assertThat(store.findingsOfMr(project.id(), 7)).singleElement()
                .satisfies(f -> assertThat(f.state()).isEqualTo(Finding.State.ADDRESSED));
        assertThat(gitlab.resolved).isEmpty();
        assertThat(gitlab.edits).anySatisfy(edit ->
                assertThat(edit).contains("~~**Addressed**~~"));
    }

    /**
     * The same rule the reviewer follows, on the reply path: a person filed this, so a person
     * closes it. The agent may say the code looks fixed — its reply on the thread does exactly
     * that — and that is all it may do.
     *
     * <p>This was the way around {@link Finding#agentMayClose()}: a comment on somebody's own
     * tracked thread came back through the replier, which closed the finding and resolved a
     * conversation mrr never opened.
     */
    @Test
    void anAddressedReplyDoesNotCloseAHumansFinding() {
        String reviewId = store.claimReview(project.id(), 8, "sha-8", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 8,
                "B.java", 4, Finding.Severity.MAJOR, "the loop overruns", "off by one",
                "disc-theirs", "note-t", true, Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, null, "ada"));
        model = new RecordingModel(List.of(Result.of(
                "{\"status\":\"addressed\",\"reply\":\"That reads fixed to me.\"}", "end_turn")));

        replier().reply(project.id(), 8, "disc-theirs", null, "fixed in HEAD", "ada");

        // The reply is posted — saying so is the whole of what the agent may do here.
        assertThat(gitlab.replies).containsExactly("disc-theirs:That reads fixed to me.");
        // And nothing else moved.
        assertThat(store.findingsOfMr(project.id(), 8).getFirst().state())
                .isEqualTo(Finding.State.OPEN);
        assertThat(store.openFindings(project.id(), 8)).hasSize(1);
        assertThat(gitlab.resolved).isEmpty();
    }

    private Replier replier() {
        return new Replier(store, ForgeFactory.fixed(gitlab), TestStores.properties(), p -> model, workers);
    }
}
