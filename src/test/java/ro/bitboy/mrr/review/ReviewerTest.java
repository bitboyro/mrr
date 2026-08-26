package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.FindingEvent;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel.Result;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.forge.Diff;
import ro.bitboy.mrr.forge.DiscussionView;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeFactory;

import java.nio.file.Path;
import java.time.Instant;
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
    private FakeForge gitlab;
    private FakeMemory memory;
    private Project project;
    private ExecutorService workers;
    private RecordingModel model;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = TestStores.persist(store, TestStores.project(alice.id(), "calc", "hook-secret"));
        gitlab = new FakeForge();
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

        reviewer.alignForge(project, 7);
        reviewer.alignForge(project, 7);

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
        reviewer.alignForge(project, 7);

        assertThat(gitlab.resolved).containsExactly("ov-1:true");
    }

    @Test
    void aLaterReviewResolvesAPriorFindingById() {
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"bug\",\"findings\":[{\"file\":\"A.java\",\"line\":1,"
                        + "\"severity\":\"major\",\"title\":\"leak\",\"body\":\"close it\"}],"
                        + "\"prior\":[]}", "end_turn")));
        reviewer().run(claim("sha-first"));
        Finding open = store.openFindings(project.id(), 7).getFirst();

        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"fixed\",\"findings\":[],\"prior\":[{\"id\":\""
                        + open.id() + "\",\"status\":\"addressed\",\"note\":\"closed\"}]}",
                        "end_turn")));
        reviewer().run(claim("sha-fix"));

        assertThat(store.openFindings(project.id(), 7)).isEmpty();
        assertThat(store.findingsOfMr(project.id(), 7).getFirst().state())
                .isEqualTo(Finding.State.ADDRESSED);
        assertThat(gitlab.resolved).contains(open.discussionId() + ":true");
    }

    /**
     * The rule the whole hybrid ledger rests on: a person reported this, so a person decides
     * it is settled. The agent may say the code looks fixed, and that is all it may do.
     */
    @Test
    void aLaterReviewProposesButDoesNotCloseAHumanFinding() {
        String reviewId = claim("sha-human");
        Finding filed = store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.BLOCKER, "leak", "close it",
                "disc-h", "n-h", true, Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, "user-alice", null));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.NEEDS_WORK, reviewId, "sha-human", 1);

        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"fixed\",\"findings\":[],\"prior\":[{\"id\":\""
                        + filed.id() + "\",\"status\":\"addressed\",\"note\":\"the reader is "
                        + "closed now\"}]}", "end_turn")));
        reviewer().run(claim("sha-later"));

        Finding after = store.findingsOfMr(project.id(), 7).getFirst();
        assertThat(after.state()).isEqualTo(Finding.State.OPEN);
        assertThat(after.resolvedAt()).isNull();
        // It still counts: the request is not clean on the agent's say-so.
        assertThat(store.openFindings(project.id(), 7)).hasSize(1);
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.NEEDS_WORK);
        assertThat(gitlab.resolved).doesNotContain("disc-h:true");
        // And it says so, on the thread, where the person who reported it will read it.
        assertThat(gitlab.replies).anySatisfy(reply -> {
            assertThat(reply).startsWith("disc-h:");
            assertThat(reply).contains("the reader is closed now");
            assertThat(reply).contains("yours to close");
        });
        assertThat(store.eventsOf(filed.id())).last().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.PROPOSED_ADDRESSED);
            assertThat(e.actorKind()).isEqualTo(FindingEvent.ActorKind.AGENT);
        });
    }

    /**
     * Once is enough. Three pushes that all judge the same human finding fixed must not put
     * three identical comments on somebody's thread.
     */
    @Test
    void theProposalIsMadeOncePerVerdictNotOncePerPush() {
        String reviewId = claim("sha-quiet");
        Finding filed = store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "close it",
                "disc-h", "n-h", true, Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, "user-alice", null));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.NEEDS_WORK, reviewId, "sha-quiet", 1);
        String verdict = "{\"summary\":\"fixed\",\"findings\":[],\"prior\":[{\"id\":\""
                + filed.id() + "\",\"status\":\"addressed\",\"note\":\"still fixed\"}]}";

        model = new RecordingModel(List.of(Result.of(verdict, "end_turn")));
        reviewer().run(claim("sha-q1"));
        model = new RecordingModel(List.of(Result.of(verdict, "end_turn")));
        reviewer().run(claim("sha-q2"));

        assertThat(gitlab.replies.stream().filter(r -> r.startsWith("disc-h:"))).hasSize(1);
        assertThat(store.eventsOf(filed.id())).filteredOn(
                        e -> e.kind() == FindingEvent.Kind.PROPOSED_ADDRESSED)
                .hasSize(1);
    }

    /**
     * A finding tracked from somebody's own comment sits on their thread. It counts — the
     * request is not clean while it stands — but mrr does not reopen or resolve a conversation
     * it did not start.
     */
    @Test
    void aTrackedThreadIsCountedButNeverResolvedOrReopened() {
        String reviewId = claim("sha-tracked");
        store.markReady(reviewId, "ov-1", "note-1");
        store.saveOverview(project.id(), 7, "ov-1", "note-1");
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "close it",
                "disc-theirs", "n-t", true, Finding.State.OPEN, Instant.now(), null, null,
                Finding.Origin.HUMAN, null, "ada"));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.NEEDS_WORK, reviewId, "sha-tracked", 1);
        gitlab.discussions.add(new ro.bitboy.mrr.forge.DiscussionView(
                "disc-theirs", "n-t", "the loop overruns", true, true, "ada", false));

        reviewer().alignForge(project, 7);

        // Their Resolve still closes it — that path is theirs and it is honoured.
        assertThat(store.findingsOfMr(project.id(), 7).getFirst().state())
                .isEqualTo(Finding.State.APPROVED);
        // But mrr never wrote to that thread itself.
        assertThat(gitlab.resolved).noneSatisfy(
                r -> assertThat(r).startsWith("disc-theirs:"));
    }

    /**
     * What a formal session buys. Zero findings is only half of "clean" — the other half is
     * that the people named as required have said they looked.
     */
    @Test
    void aFormalSessionIsNotCleanUntilItsRequiredPeopleSignOff() {
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.REVIEWING, null, "sha", 0);
        store.setSession(project.id(), 7, MrStatus.Mode.FORMAL,
                MrStatus.SessionState.UNDER_REVIEW);
        store.addParticipant(new ro.bitboy.mrr.model.SessionParticipant(
                project.id(), 7, "romanbotnari",
                ro.bitboy.mrr.model.SessionParticipant.Kind.FORGE, "reviewer", true, null,
                Instant.now()));
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"fine\",\"findings\":[],\"prior\":[]}", "end_turn")));

        reviewer().run(claim("sha-formal"));

        // Nothing is wrong with the code, and the request is still not clean.
        assertThat(store.openFindings(project.id(), 7)).isEmpty();
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.NEEDS_WORK);
        assertThat(store.outstandingSignOffs(project.id(), 7)).isEqualTo(1);
    }

    /** An ad-hoc cycle — which is nearly all of them — is untouched by any of that. */
    @Test
    void anAdHocCycleIsCleanOnFindingsAlone() {
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"fine\",\"findings\":[],\"prior\":[]}", "end_turn")));

        reviewer().run(claim("sha-adhoc"));

        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.CLEAN);
    }

    /**
     * A sign-off is on a commit, not on a merge request. Approving what you read yesterday is
     * not approving what was pushed since.
     */
    @Test
    void aNewCommitClearsEverySignOff() {
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.CLEAN, null, "sha", 0);
        store.setSession(project.id(), 7, MrStatus.Mode.FORMAL,
                MrStatus.SessionState.UNDER_REVIEW);
        store.addParticipant(new ro.bitboy.mrr.model.SessionParticipant(
                project.id(), 7, "ada", ro.bitboy.mrr.model.SessionParticipant.Kind.FORGE,
                "reviewer", true, null, Instant.now()));
        store.signOff(project.id(), 7, "ada");
        assertThat(store.outstandingSignOffs(project.id(), 7)).isZero();

        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"fine\",\"findings\":[],\"prior\":[]}", "end_turn")));
        reviewer().run(claim("sha-pushed"));

        assertThat(store.outstandingSignOffs(project.id(), 7)).isEqualTo(1);
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.NEEDS_WORK);
    }

    /** An agent finding is the loop that already worked, and it stays untouched. */
    @Test
    void anAgentFindingIsStillClosedByTheAgent() {
        String reviewId = claim("sha-agent");
        Finding filed = store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "close it",
                "disc-a", "n-a", true, Finding.State.OPEN, Instant.now(), null));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.NEEDS_WORK, reviewId, "sha-agent", 1);

        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"fixed\",\"findings\":[],\"prior\":[{\"id\":\""
                        + filed.id() + "\",\"status\":\"addressed\",\"note\":\"closed\"}]}",
                        "end_turn")));
        reviewer().run(claim("sha-agent-2"));

        assertThat(store.findingsOfMr(project.id(), 7).getFirst().state())
                .isEqualTo(Finding.State.ADDRESSED);
        assertThat(gitlab.resolved).contains("disc-a:true");
    }

    @Test
    void alignGitlabReResolvesAnAddressedThreadThatGitLabReopened() {
        String reviewId = claim("sha-addr");
        store.markReady(reviewId, "ov-1", "note-1");
        store.saveOverview(project.id(), 7, "ov-1", "note-1");
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "close it",
                "disc-find", "n-2", true, Finding.State.ADDRESSED, Instant.now(), Instant.now()));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.CLEAN, reviewId, "sha-addr", 0);
        gitlab.discussions.add(new ro.bitboy.mrr.forge.DiscussionView(
                "ov-1", "note-1", "### mrr review", true, true, gitlab.tokenUser, false));
        gitlab.discussions.add(new ro.bitboy.mrr.forge.DiscussionView(
                "disc-find", "n-2", "**[Major]** leak", true, false, null, false));

        reviewer().alignForge(project, 7);

        assertThat(gitlab.resolved).contains("disc-find:true");
    }

    /**
     * The point of the feature: pressing Resolve in GitLab closes the finding, records who
     * pressed it, and the thread is left alone instead of being reopened on the next scan.
     */
    @Test
    void alignGitlabAdoptsAHandResolveAsAnApproval() {
        String reviewId = claim("sha-open");
        store.markReady(reviewId, "ov-1", "note-1");
        store.saveOverview(project.id(), 7, "ov-1", "note-1");
        Finding open = store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "close it",
                "disc-find", "n-2", true, Finding.State.OPEN, Instant.now(), null));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.NEEDS_WORK, reviewId, "sha-open", 1);
        gitlab.discussions.add(new ro.bitboy.mrr.forge.DiscussionView(
                "ov-1", "note-1", "### mrr review", true, false, null, false));
        gitlab.discussions.add(new ro.bitboy.mrr.forge.DiscussionView(
                "disc-find", "n-2", "**[Major]** leak", true, false, null, false));
        gitlab.resolveAs("disc-find", "romanbotnari");

        reviewer().alignForge(project, 7);

        Finding after = store.findingsOfMr(project.id(), 7).getFirst();
        assertThat(after.state()).isEqualTo(Finding.State.APPROVED);
        assertThat(after.approvedBy()).isEqualTo("romanbotnari");
        assertThat(after.resolvedAt()).isNotNull();
        assertThat(store.openFindings(project.id(), 7)).isEmpty();
        assertThat(gitlab.resolved).doesNotContain("disc-find:false");
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.CLEAN);
        assertThat(open.state()).isEqualTo(Finding.State.OPEN);
        // The sign-off is on the record as a human act against a forge username, which is
        // what makes it distinguishable from the agent closing its own finding.
        assertThat(store.eventsOf(after.id())).last().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.APPROVED);
            assertThat(e.actorKind()).isEqualTo(FindingEvent.ActorKind.HUMAN);
            assertThat(e.actorForge()).isEqualTo("romanbotnari");
        });
    }

    /**
     * mrr resolves threads with its own PAT. Reading one of those back as an approval would
     * let it sign off on its own findings.
     */
    @Test
    void alignGitlabDoesNotTreatMrrsOwnResolveAsAnApproval() {
        String reviewId = claim("sha-own");
        store.markReady(reviewId, "ov-1", "note-1");
        store.saveOverview(project.id(), 7, "ov-1", "note-1");
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "close it",
                "disc-find", "n-2", true, Finding.State.OPEN, Instant.now(), null));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.NEEDS_WORK, reviewId, "sha-own", 1);
        gitlab.discussions.add(new ro.bitboy.mrr.forge.DiscussionView(
                "disc-find", "n-2", "**[Major]** leak", true, true, gitlab.tokenUser, false));

        reviewer().alignForge(project, 7);

        Finding after = store.findingsOfMr(project.id(), 7).getFirst();
        assertThat(after.state()).isEqualTo(Finding.State.OPEN);
        assertThat(after.approvedBy()).isNull();
        assertThat(gitlab.resolved).contains("disc-find:false");
    }

    /**
     * The lookup that tells mrr's own PAT apart from a person is a network call. When it
     * fails mrr cannot tell the two apart — and the resolve is still adopted, because
     * dropping it would reopen the thread under a human who already signed it off.
     */
    @Test
    void alignGitlabAdoptsAHandResolveWhenTheTokenUserLookupFails() {
        String reviewId = claim("sha-nouser");
        store.markReady(reviewId, "ov-1", "note-1");
        store.saveOverview(project.id(), 7, "ov-1", "note-1");
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "close it",
                "disc-find", "n-2", true, Finding.State.OPEN, Instant.now(), null));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.NEEDS_WORK, reviewId, "sha-nouser", 1);
        gitlab.discussions.add(new ro.bitboy.mrr.forge.DiscussionView(
                "disc-find", "n-2", "**[Major]** leak", true, false, null, false));
        gitlab.resolveAs("disc-find", "romanbotnari");
        gitlab.tokenUserFailure = new IllegalStateException("GitLab GET /user failed with 401");

        reviewer().alignForge(project, 7);

        Finding after = store.findingsOfMr(project.id(), 7).getFirst();
        assertThat(after.state()).isEqualTo(Finding.State.APPROVED);
        assertThat(after.approvedBy()).isEqualTo("romanbotnari");
        assertThat(gitlab.resolved).doesNotContain("disc-find:false");
    }

    @Test
    void unparseableOutputFailsClosedAndKeepsTheResponse() {
        String prose = "Here is a nice review in markdown. Looks fine.";
        model = new RecordingModel(List.of(Result.of(prose, "end_turn")));
        String id = claim("sha-prose");

        reviewer().run(id);

        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.FAILED);
        assertThat(review.errorKind()).isEqualTo(FailureKind.OUTPUT);
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

    @Test
    void aDiffLargerThanTheWindowIsReviewedInSlicesThenMerged() {
        String a = "--- A.java\n+" + "a".repeat(200) + "\n";
        String b = "--- B.java\n+" + "b".repeat(200) + "\n";
        gitlab.diff = new ro.bitboy.mrr.forge.Diff(
                "title", "desc", "fix/x", "main", "https://gitlab/mr/7",
                "Ada", "ada", List.of("A.java", "B.java"), a + b, false);
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"A ok\",\"findings\":[],\"prior\":[]}", "end_turn"),
                Result.of("{\"summary\":\"B ok\",\"findings\":[{\"file\":\"B.java\",\"line\":1,"
                        + "\"severity\":\"minor\",\"title\":\"noise\",\"body\":\"n\"}],\"prior\":[]}",
                        "end_turn"),
                Result.of("{\"summary\":\"Both files reviewed\",\"findings\":[{\"file\":\"B.java\","
                        + "\"line\":1,\"severity\":\"minor\",\"title\":\"noise\",\"body\":\"n\"}],"
                        + "\"prior\":[]}", "end_turn")));
        String id = claim("sha-slices");

        reviewer(new ro.bitboy.mrr.MrrProperties("/tmp/root-token.txt", "http://localhost:8130",
                "/tmp/memory", new ro.bitboy.mrr.MrrProperties.Review(
                        1, 256, 30, 8_000, 8_000, 8_000, 2, 8_000, 8))).run(id);

        assertThat(model.turns).hasSize(3);
        assertThat(model.turns.get(0).getFirst().text()).contains("slice 1 of 2", "A.java")
                .doesNotContain("--- B.java");
        assertThat(model.turns.get(1).getFirst().text()).contains("slice 2 of 2", "B.java")
                .doesNotContain("--- A.java");
        assertThat(model.turns.get(2).getFirst().text()).contains("Merge the partial", "A ok", "B ok");
        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.READY);
        assertThat(review.prompt()).contains("Reviewed in 2 slices");
        assertThat(gitlab.posted).anyMatch(s -> s.contains("Both files reviewed"));
    }

    @Test
    void aMalformedSynthesisFailsClosed() {
        String a = "--- A.java\n+" + "a".repeat(200) + "\n";
        String b = "--- B.java\n+" + "b".repeat(200) + "\n";
        gitlab.diff = new ro.bitboy.mrr.forge.Diff(
                "title", "desc", "fix/x", "main", "https://gitlab/mr/7",
                "Ada", "ada", List.of("A.java", "B.java"), a + b, false);
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"A ok\",\"findings\":[],\"prior\":[]}", "end_turn"),
                Result.of("{\"summary\":\"B ok\",\"findings\":[],\"prior\":[]}", "end_turn"),
                Result.of("not json", "end_turn")));
        String id = claim("sha-bad-merge");

        reviewer(new ro.bitboy.mrr.MrrProperties("/tmp/root-token.txt", "http://localhost:8130",
                "/tmp/memory", new ro.bitboy.mrr.MrrProperties.Review(
                        1, 256, 30, 8_000, 8_000, 8_000, 2, 8_000, 8))).run(id);

        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.FAILED);
        assertThat(review.response()).isEqualTo("not json");
        assertThat(gitlab.posted).isEmpty();
    }

    @Test
    void emptyJsonWithoutASummaryFailsClosed() {
        model = new RecordingModel(List.of(Result.of("{}", "end_turn")));
        String id = claim("sha-empty-json");

        reviewer().run(id);

        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.FAILED);
        assertThat(review.response()).isEqualTo("{}");
        assertThat(gitlab.posted).isEmpty();
    }

    @Test
    void aForgeOutageWhilePostingFindingsFailsClosed() {
        gitlab.postFailure = new ro.bitboy.mrr.web.ApiException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "forge_error",
                "GitLab POST /discussions failed with 502");
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"bug\",\"findings\":[{\"file\":\"A.java\",\"line\":1,"
                        + "\"severity\":\"major\",\"title\":\"leak\",\"body\":\"close it\"}],"
                        + "\"prior\":[]}", "end_turn")));
        String id = claim("sha-post-fail");

        reviewer().run(id);

        Review review = store.findReview(id).orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.FAILED);
        assertThat(review.errorKind()).isEqualTo(FailureKind.FORGE);
        assertThat(review.response()).contains("leak");
        assertThat(store.findingsOfMr(project.id(), 7)).isEmpty();
        assertThat(gitlab.posted).isEmpty();
    }

    // ── hosts that cannot do everything GitLab can ───────────────────────────────────

    @Test
    void aHostThatCannotResolveThreadsStillRecordsTheFindingAndMarksTheComment() {
        // Internal state is never weakened by a host limitation: the finding is ADDRESSED
        // either way. What changes is that the comment says so, because the host cannot
        // close the thread.
        gitlab.capabilities = new ForgeCapabilities(true, false, true, true, false, true, true);
        String reviewId = claim("sha-prior");
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 7,
                "A.java", 1, Finding.Severity.MAJOR, "leak", "**[Major]** leak\n\nclose it",
                "disc-find", "n-2", true, Finding.State.OPEN, Instant.now(), null));
        String findingId = store.openFindings(project.id(), 7).getFirst().id();
        model = new RecordingModel(List.of(Result.of(
                "{\"summary\":\"fixed\",\"findings\":[],\"prior\":[{\"id\":\""
                        + findingId + "\",\"status\":\"addressed\",\"note\":\"closed\"}]}",
                "end_turn")));

        reviewer().run(claim("sha-fix"));

        assertThat(store.findingsOfMr(project.id(), 7))
                .singleElement()
                .satisfies(f -> assertThat(f.state()).isEqualTo(Finding.State.ADDRESSED));
        assertThat(gitlab.resolved).isEmpty();
        assertThat(gitlab.edits).anySatisfy(edit ->
                assertThat(edit).startsWith("disc-find:~~**Addressed**~~"));
    }

    @Test
    void theMarkerIsNotStackedWhenAReviewRunsAgain() {
        assertThat(Prompts.withAddressedMarker(
                Prompts.withAddressedMarker("**[Major]** leak")))
                .isEqualTo(Prompts.withAddressedMarker("**[Major]** leak"));
    }

    @Test
    void aHostThatCannotEditCommentsPostsTheOverviewOnceAndLeavesIt() {
        gitlab.capabilities = new ForgeCapabilities(true, true, true, true, true, true, false);
        model = new RecordingModel(List.of(
                Result.of("{\"summary\":\"ok\",\"findings\":[],\"prior\":[]}", "end_turn"),
                Result.of("{\"summary\":\"still ok\",\"findings\":[],\"prior\":[]}", "end_turn")));
        Reviewer reviewer = reviewer();

        reviewer.run(claim("sha-1"));
        reviewer.run(claim("sha-2"));

        // Two reviews, one overview, and never an edit — a repost on every commit is exactly
        // the noise the one-thread rule exists to prevent.
        assertThat(gitlab.posted).hasSize(1);
        assertThat(gitlab.edits).isEmpty();
    }

    @Test
    void retractingAnApprovalCarriesAReasonSoADismissalCanExplainItself() {
        Project approving = store.findProject(project.id()).orElseThrow();
        store.updateProject(new Project(approving.id(), approving.ownerId(), approving.name(),
                true, approving.forge(), approving.gitlabUrl(), approving.gitlabProjectId(),
                approving.gitlabToken(), approving.gitlabOperatorToken(),
                approving.webhookSecret(), approving.llmProviderId(), approving.llm(),
                approving.skill(), false, 1, List.of(), true, false, null, null, null,
                null, null, null, approving.createdAt()));
        // mrr approved at the previous commit; this one reintroduces a finding.
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.CLEAN, null, "sha-clean", 0);
        store.markMrrApproved(project.id(), 7, "sha-clean");
        model = new RecordingModel(List.of(Result.of(
                "{\"summary\":\"regressed\",\"findings\":[{\"file\":\"A.java\",\"line\":1,"
                        + "\"severity\":\"major\",\"title\":\"leak\",\"body\":\"close it\"}],"
                        + "\"prior\":[]}", "end_turn")));

        reviewer().run(claim("sha-regress"));

        assertThat(gitlab.unapprovals).singleElement()
                .satisfies(reason -> assertThat(reason).contains("1 finding(s)"));
    }

    private String claim(String sha) {
        return store.claimReview(project.id(), 7, sha, "{}", TestStores.mrInfo()).review().id();
    }

    private Reviewer reviewer() {
        return reviewer(TestStores.properties());
    }

    private Reviewer reviewer(ro.bitboy.mrr.MrrProperties properties) {
        return new Reviewer(store, ForgeFactory.fixed(gitlab), properties, new ObjectMapper(),
                memory, p -> model, workers);
    }
}
