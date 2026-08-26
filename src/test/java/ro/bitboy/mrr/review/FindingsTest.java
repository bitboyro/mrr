package ro.bitboy.mrr.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.ForgeCapabilities;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.FindingEvent;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Findings a person files, rather than the reviewer.
 *
 * <p>The contract under test is that these are the same objects — one row, one thread, counted
 * the same against the merge gate — with two differences: the author is on the record, and an
 * agent may not close one.
 */
class FindingsTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeForge forge;
    private Findings findings;
    private Findings.Actor alice;
    private Project project;
    private String reviewId;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User user = store.createUser("alice", Role.USER);
        alice = Findings.Actor.of(new Principal(user.id(), user.name(), user.role()));
        project = TestStores.persist(store,
                TestStores.withOperator(TestStores.project(user.id(), "calc", "secret"), "op-pat"));
        forge = new FakeForge();
        forge.refs = Optional.of(new FakeForge.Anchor("sha"));
        findings = new Findings(store, ForgeFactory.fixed(forge));
        reviewId = store.claimReview(project.id(), 7, "sha", "{}", TestStores.mrInfo())
                .review().id();
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.CLEAN, reviewId, "sha", 0);
    }

    private Findings.Draft draft() {
        return new Findings.Draft("A.java", 12, Finding.Severity.BLOCKER,
                "Overruns the array", "The loop runs one past the end.");
    }

    @Test
    void aPersonFilesAFindingThatCountsLikeAnyOther() {
        forge.inlineDiscussion = new Discussion("disc-h", "note-h");

        Finding filed = findings.file(alice, project, 7, draft());

        assertThat(filed.origin()).isEqualTo(Finding.Origin.HUMAN);
        assertThat(filed.authorUser()).isEqualTo(alice.userId());
        assertThat(filed.authorForge()).isNull();
        assertThat(filed.state()).isEqualTo(Finding.State.OPEN);
        assertThat(filed.inline()).isTrue();
        assertThat(filed.discussionId()).isEqualTo("disc-h");
        assertThat(forge.inlineAttempts).containsExactly("A.java:12");
        // Attributed, and posted as the operator rather than the review bot — a finding under
        // the agent's name is the confusion the origin column exists to prevent.
        assertThat(forge.posted).singleElement().satisfies(body -> {
            assertThat(body).contains("**[Blocker]** Overruns the array");
            assertThat(body).contains("Filed by alice");
        });
        assertThat(forge.lastToken).isEqualTo("op-pat");
        // It counts. The cycle is not clean any more.
        assertThat(store.openFindings(project.id(), 7)).hasSize(1);
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.NEEDS_WORK);
        assertThat(store.eventsOf(filed.id())).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.CREATED);
            assertThat(e.actorKind()).isEqualTo(FindingEvent.ActorKind.HUMAN);
            assertThat(e.actorUser()).isEqualTo(alice.userId());
        });
    }

    /** A rejected position is routine, not an error — the same fallback the reviewer uses. */
    @Test
    void aPositionTheHostWillNotTakeFallsBackToAnUnanchoredThread() {
        Finding filed = findings.file(alice, project, 7, draft());

        assertThat(forge.inlineAttempts).containsExactly("A.java:12");
        assertThat(filed.inline()).isFalse();
        assertThat(filed.discussionId()).isEqualTo("ov-1");
    }

    /**
     * Without a second identity there is no way to post as the person rather than as the bot,
     * and quietly using the review token would put their finding under the agent's name.
     */
    @Test
    void filingNeedsAnOperatorTokenAndSaysSo() {
        Project noOperator = TestStores.persist(store,
                TestStores.project(alice.userId(), "other", "secret"));
        store.upsertMrStatus(noOperator.id(), 7, MrStatus.Status.CLEAN, reviewId, "sha", 0);

        assertThatThrownBy(() -> findings.file(alice, noOperator, 7, draft()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("gitlabOperatorToken");
        assertThat(forge.posted).isEmpty();
    }

    /** review_id is NOT NULL, and a finding is filed against the commit the request was at. */
    @Test
    void filingNeedsTheRequestToHaveBeenReviewedOnce() {
        store.upsertMrStatus(project.id(), 9, MrStatus.Status.REVIEWING, null, null, 0);

        assertThatThrownBy(() -> findings.file(alice, project, 9, draft()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("has not reviewed this merge request yet");
    }

    @Test
    void aTitleIsRequired() {
        assertThatThrownBy(() -> findings.file(alice, project, 7,
                new Findings.Draft(null, null, null, "  ", "body")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("title is required");
    }

    /**
     * The state Crucible had and mrr did not: real, not being fixed, and the record says who
     * accepted the risk.
     */
    @Test
    void wontFixClosesTheFindingAndItsThread() {
        Finding filed = findings.file(alice, project, 7, draft());

        Finding after = findings.triage(alice, project, filed, Finding.State.WONT_FIX,
                "we accept the risk on this path");

        assertThat(after.state()).isEqualTo(Finding.State.WONT_FIX);
        assertThat(after.resolvedAt()).isNotNull();
        assertThat(store.openFindings(project.id(), 7)).isEmpty();
        assertThat(store.findMrStatus(project.id(), 7).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.CLEAN);
        assertThat(forge.resolved).contains("ov-1:true");
        assertThat(store.eventsOf(filed.id())).last().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.WONT_FIX);
            assertThat(e.actorKind()).isEqualTo(FindingEvent.ActorKind.HUMAN);
            assertThat(e.detail()).isEqualTo("we accept the risk on this path");
        });
    }

    /** Reopening must not leave a sign-off standing on a finding that is live again. */
    @Test
    void reopeningClearsTheEarlierSignOff() {
        Finding filed = findings.file(alice, project, 7, draft());
        findings.triage(alice, project, filed, Finding.State.APPROVED, "fine as it is");
        Finding approved = store.findFinding(filed.id()).orElseThrow();
        assertThat(approved.resolvedAt()).isNotNull();

        Finding reopened = findings.triage(alice, project, approved, Finding.State.OPEN,
                "it came back");

        assertThat(reopened.state()).isEqualTo(Finding.State.OPEN);
        assertThat(reopened.resolvedAt()).isNull();
        assertThat(reopened.approvedBy()).isNull();
        assertThat(forge.resolved).contains("ov-1:false");
        assertThat(store.eventsOf(filed.id())).last()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(FindingEvent.Kind.REOPENED));
    }

    /**
     * ADDRESSED is a claim that a commit fixed the problem. That is a verdict the reviewer
     * reaches by reading the diff, not a label anybody can apply.
     */
    @Test
    void addressedCannotBeSetByHand() {
        Finding filed = findings.file(alice, project, 7, draft());

        assertThatThrownBy(() ->
                findings.triage(alice, project, filed, Finding.State.ADDRESSED, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("the reviewer's verdict");
    }

    /** Two people triaging at once must not silently overwrite each other. */
    @Test
    void triagingAFindingThatAlreadyMovedIsAConflict() {
        Finding filed = findings.file(alice, project, 7, draft());
        findings.triage(alice, project, filed, Finding.State.WONT_FIX, null);

        assertThatThrownBy(() ->
                findings.triage(alice, project, filed, Finding.State.DUPLICATE, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already moved");
    }

    /**
     * The decision behind having no DISPUTED state: a contested finding keeps counting. A
     * blocker that stops counting because somebody argued with it is how a ledger starts lying.
     */
    @Test
    void disputingRecordsTheDisagreementAndChangesNothingElse() {
        Finding filed = findings.file(alice, project, 7, draft());

        findings.dispute(alice, filed, "the input is validated upstream");

        assertThat(store.findFinding(filed.id()).orElseThrow().state())
                .isEqualTo(Finding.State.OPEN);
        assertThat(store.openFindings(project.id(), 7)).hasSize(1);
        assertThat(forge.resolved).isEmpty();
        assertThat(store.eventsOf(filed.id())).last().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.DISPUTED);
            assertThat(e.actorKind()).isEqualTo(FindingEvent.ActorKind.HUMAN);
            assertThat(e.detail()).isEqualTo("the input is validated upstream");
        });
    }

    /**
     * The human-in-the-loop option. A held finding is a draft: nothing reaches the forge, but
     * it counts against the merge gate, because nobody has decided it is wrong — only that
     * nobody has looked.
     */
    @Test
    void aHeldFindingIsCountedButNotPosted() {
        Finding held = store.createFinding(new Finding(
                ro.bitboy.mrr.auth.Ids.newId(), reviewId, project.id(), 7, "A.java", 12,
                Finding.Severity.BLOCKER, "leak", "close it", null, null, false,
                Finding.State.PENDING, java.time.Instant.now(), null));

        assertThat(store.openFindings(project.id(), 7)).extracting(Finding::id)
                .containsExactly(held.id());
        assertThat(held.state().isClosed()).isFalse();
        assertThat(forge.posted).isEmpty();
    }

    /** Releasing posts it as the reviewer would have, and records who let it through. */
    @Test
    void releasingAHeldFindingPostsItAndOpensIt() {
        Finding held = pending();

        Finding released = findings.release(alice, project, held, null, null);

        assertThat(released.state()).isEqualTo(Finding.State.OPEN);
        assertThat(released.discussionId()).isEqualTo("ov-1");
        assertThat(forge.posted).hasSize(1);
        // Under the review token: it is still the reviewer's finding, and releasing it says a
        // person read it rather than that they wrote it.
        assertThat(forge.lastToken).isEqualTo("glpat-x");
        assertThat(store.eventsOf(held.id())).last().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(FindingEvent.Kind.RELEASED);
            assertThat(e.actorKind()).isEqualTo(FindingEvent.ActorKind.HUMAN);
            assertThat(e.actorUser()).isEqualTo(alice.userId());
        });
    }

    @Test
    void aHeldFindingCanBeEditedOnItsWayOut() {
        Finding held = pending();

        Finding released = findings.release(alice, project, held, "Overruns on the last row",
                "Rewritten before posting.");

        assertThat(released.title()).isEqualTo("Overruns on the last row");
        assertThat(forge.posted).singleElement().isEqualTo("Rewritten before posting.");
    }

    /**
     * Discarding is deliberately its own state: nothing was posted and nobody accepted a risk,
     * so recording it as WONT_FIX would put a claim on the record that nobody made.
     */
    @Test
    void discardingAHeldFindingIsNotAnAcceptedRisk() {
        Finding held = pending();

        Finding dropped = findings.discard(alice, project, held, "not a real problem");

        assertThat(dropped.state()).isEqualTo(Finding.State.DISCARDED);
        assertThat(store.openFindings(project.id(), 7)).isEmpty();
        assertThat(forge.posted).isEmpty();
        assertThat(store.eventsOf(held.id())).last()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(FindingEvent.Kind.DISCARDED));
    }

    /**
     * Two people who both loaded the page must not put the same comment on the request twice.
     * The stale in-memory finding is passed on purpose: that is exactly what the second caller
     * would hold, and checking it rather than the row is the bug this pins.
     */
    @Test
    void aFindingCanOnlyBeReleasedOnce() {
        Finding held = pending();
        findings.release(alice, project, held, null, null);

        assertThatThrownBy(() -> findings.release(alice, project, held, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only a held finding can be released");
        assertThat(forge.posted).hasSize(1);
    }

    /** A post that fails hands the claim back, so the finding is offered again, not lost. */
    @Test
    void aReleaseThatCannotPostLeavesTheFindingHeld() {
        Finding held = pending();
        forge.postFailure = new IllegalStateException("forge is down");

        assertThatThrownBy(() -> findings.release(alice, project, held, null, null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.findFinding(held.id()).orElseThrow().state())
                .isEqualTo(Finding.State.PENDING);
    }

    @Test
    void aPostedFindingCannotBeDiscarded() {
        Finding filed = findings.file(alice, project, 7, draft());

        assertThatThrownBy(() -> findings.discard(alice, project, filed, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only a held finding can be discarded");
    }

    /**
     * The other half of the formal rule: once the last required person signs off, the request
     * goes clean immediately rather than lagging until the next push.
     */
    @Test
    void signingOffTheLastRequiredParticipantMakesItClean() {
        formalWithRequired("ada");

        assertThat(findings.refresh(project.id(), 7).currentStatus())
                .isEqualTo(MrStatus.Status.NEEDS_WORK);

        store.signOff(project.id(), 7, "ada");

        assertThat(findings.refresh(project.id(), 7).currentStatus())
                .isEqualTo(MrStatus.Status.CLEAN);
    }

    /** An open finding still blocks, however many people have signed off. */
    @Test
    void signingOffDoesNotOverrideAnOpenFinding() {
        formalWithRequired("ada");
        store.signOff(project.id(), 7, "ada");
        findings.file(alice, project, 7, draft());

        assertThat(findings.refresh(project.id(), 7).currentStatus())
                .isEqualTo(MrStatus.Status.NEEDS_WORK);
    }

    /**
     * The ordinary case: a redelivery arriving after the first one has landed, caught by the
     * existence check.
     *
     * <p>The concurrent case — two deliveries passing that check together — is settled by the
     * unique index instead, and is pinned in {@code StoreTest} against the insert itself.
     * Testing it here would prove only that the check works, which is the half that a race
     * slips past.
     */
    @Test
    void trackingTheSameThreadTwiceFilesOneFinding() {
        ro.bitboy.mrr.forge.ForgeEvent.Note note = new ro.bitboy.mrr.forge.ForgeEvent.Note(
                "disc-theirs", "n-1", "the loop overruns", "ada", false, true, "A.java", 12);
        Findings.Actor ada = Findings.Actor.onForge("ada");

        assertThat(findings.track(ada, project, 7, note, note.body(), note.body())).isPresent();
        assertThat(findings.track(ada, project, 7, note, note.body(), note.body())).isEmpty();

        assertThat(store.findingsOfMr(project.id(), 7)).hasSize(1);
    }

    /**
     * Events reference findings, so deleting a project has to unwind them in that order or the
     * foreign key refuses — on Postgres always, and on SQLite whenever they are switched on.
     */
    @Test
    void deletingAProjectTakesItsEventsAndParticipantsWithIt() {
        Finding filed = findings.file(alice, project, 7, draft());
        store.addParticipant(new ro.bitboy.mrr.model.SessionParticipant(
                project.id(), 7, "ada", ro.bitboy.mrr.model.SessionParticipant.Kind.FORGE,
                "reviewer", true, null, java.time.Instant.now()));
        assertThat(store.eventsOf(filed.id())).isNotEmpty();

        assertThat(store.deleteProject(project.id())).isTrue();

        assertThat(store.eventsOf(filed.id())).isEmpty();
        assertThat(store.participants(project.id(), 7)).isEmpty();
        assertThat(store.findingsOfMr(project.id(), 7)).isEmpty();
    }

    /**
     * The rule this whole branch exists for, at the one place it was still breakable. A review
     * takes minutes; the reviewer read this finding as OPEN before the model ran, and somebody
     * closed it in between. The agent must not overwrite that.
     */
    @Test
    void anAgentVerdictCannotOverwriteADecisionMadeWhileItWasThinking() {
        Finding filed = findings.file(alice, project, 7, draft());
        findings.triage(alice, project, filed, Finding.State.WONT_FIX, "we accept it");

        // `filed` is the stale copy the reviewer is still holding — state OPEN.
        store.markAddressed(FindingEvent.addressedByReview(filed, reviewId, "sha-2", "looks fixed"));

        Finding after = store.findFinding(filed.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(Finding.State.WONT_FIX);
        assertThat(store.eventsOf(filed.id())).noneSatisfy(
                e -> assertThat(e.kind()).isEqualTo(FindingEvent.Kind.ADDRESSED));
    }

    /**
     * DISCARDED says nobody ever saw it. Reaching it on a posted finding would close a real
     * problem without anybody accepting the risk that WONT_FIX puts on the record.
     */
    @Test
    void aPostedFindingCannotBeDiscardedThroughTheGenericStatePath() {
        Finding filed = findings.file(alice, project, 7, draft());

        assertThatThrownBy(() ->
                findings.triage(alice, project, filed, Finding.State.DISCARDED, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only for a finding that was never posted");

        assertThat(store.findFinding(filed.id()).orElseThrow().state())
                .isEqualTo(Finding.State.OPEN);
    }

    private void formalWithRequired(String forgeUsername) {
        store.setSession(project.id(), 7, MrStatus.Mode.FORMAL,
                MrStatus.SessionState.UNDER_REVIEW);
        store.addParticipant(new ro.bitboy.mrr.model.SessionParticipant(
                project.id(), 7, forgeUsername,
                ro.bitboy.mrr.model.SessionParticipant.Kind.FORGE, "reviewer", true, null,
                java.time.Instant.now()));
    }

    private Finding pending() {
        return store.createFinding(new Finding(
                ro.bitboy.mrr.auth.Ids.newId(), reviewId, project.id(), 7, "A.java", 12,
                Finding.Severity.BLOCKER, "leak", "close it", null, null, false,
                Finding.State.PENDING, java.time.Instant.now(), null));
    }

    /** mrr's record is never weakened by a host limitation; only the thread half is skipped. */
    @Test
    void aHostThatCannotCloseThreadsStillMovesTheFinding() {
        forge.capabilities = new ForgeCapabilities(true, false, true, true, true, true, true);
        Finding filed = findings.file(alice, project, 7, draft());

        Finding after = findings.triage(alice, project, filed, Finding.State.WONT_FIX, null);

        assertThat(after.state()).isEqualTo(Finding.State.WONT_FIX);
        assertThat(forge.resolved).isEmpty();
    }
}
