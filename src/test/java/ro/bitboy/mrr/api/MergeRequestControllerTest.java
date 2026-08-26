package ro.bitboy.mrr.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.Discussion;
import ro.bitboy.mrr.forge.FilePatch;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.forge.MrCommit;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.SessionParticipant;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.forge.ForgeFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MergeRequestControllerTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeForge gitlab;
    private FakeMemory memory;
    private MergeRequestController controller;
    private FakeReviewer reviewer;
    private Principal alice;
    private Project project;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User user = store.createUser("alice", Role.USER);
        alice = new Principal(user.id(), user.name(), user.role());
        project = TestStores.persist(store, TestStores.project(user.id(), "calc", "secret"));
        store.upsertMrStatus(project.id(), 7, MrStatus.Status.CLEAN, null, "sha", 0);
        gitlab = new FakeForge();
        memory = new FakeMemory();
        reviewer = new FakeReviewer();
        ForgeFactory forges = ForgeFactory.fixed(gitlab);
        controller = new MergeRequestController(store, forges, memory,
                new ro.bitboy.mrr.review.Findings(store, forges),
                new ro.bitboy.mrr.review.Trigger(store, forges, reviewer));
    }

    /**
     * The point of the endpoint: a request mrr has never seen, reviewed because somebody asked
     * rather than because a webhook arrived. It reads the merge request live from the forge, so
     * no cycle has to exist first.
     */
    @Test
    void reviewNowQueuesARequestMrrHasNeverSeen() {
        gitlab.oneMr = Optional.of(new MergeRequest(
                31, "Add the guard", "opened", "sha-live", "https://gl/31", false));

        ResponseEntity<Review> response = controller.reviewNow(alice, project.id(), 31);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Review queued = store.findReview(project.id(), 31, "sha-live").orElseThrow();
        assertThat(queued.state()).isEqualTo(Review.State.PENDING);
        assertThat(reviewer.submitted).containsExactly(queued.id());
        assertThat(store.findMrStatus(project.id(), 31).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.REVIEWING);
    }

    /**
     * Reviews are keyed on the commit, and that index is what makes redelivered webhooks free.
     * Asking again on an unchanged commit has to be free for the same reason.
     */
    @Test
    void askingTwiceOnTheSameCommitSpendsNothing() {
        gitlab.oneMr = Optional.of(new MergeRequest(
                31, "Add the guard", "opened", "sha-live", "https://gl/31", false));
        controller.reviewNow(alice, project.id(), 31);
        reviewer.submitted.clear();

        ResponseEntity<Review> again = controller.reviewNow(alice, project.id(), 31);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewer.submitted).isEmpty();
        assertThat(store.listReviews(alice, project.id(), 31L, 10)).hasSize(1);
    }

    /**
     * skipDrafts exists so an unfinished branch does not cost a model call on every push.
     * Asking for one explicitly already answers that question.
     */
    @Test
    void aDraftIsReviewedWhenSomebodyAsksForIt() {
        Project drafty = TestStores.persist(store, TestStores.project(
                alice.userId(), "drafty", "secret", true, true));
        gitlab.oneMr = Optional.of(new MergeRequest(
                31, "WIP", "opened", "sha-draft", "https://gl/31", true));

        ResponseEntity<Review> response = controller.reviewNow(alice, drafty.id(), 31);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reviewer.submitted).hasSize(1);
    }

    /** Findings on a request the forge has ended are history, not a merge gate. */
    @Test
    void aMergedRequestIsNotReviewed() {
        gitlab.oneMr = Optional.of(new MergeRequest(
                31, "done", "merged", "sha-live", "https://gl/31", false));

        assertThatThrownBy(() -> controller.reviewNow(alice, project.id(), 31))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void aRequestTheForgeDoesNotHaveIs404() {
        gitlab.oneMr = Optional.empty();

        assertThatThrownBy(() -> controller.reviewNow(alice, project.id(), 999))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** Disabling a project is deliberate, and a manual trigger must not quietly override it. */
    @Test
    void aDisabledProjectIsNotReviewedOnDemand() {
        Project off = TestStores.persist(store, TestStores.project(
                alice.userId(), "off", "secret", false, false));
        gitlab.oneMr = Optional.of(new MergeRequest(
                31, "x", "opened", "sha-live", "https://gl/31", false));

        assertThatThrownBy(() -> controller.reviewNow(alice, off.id(), 31))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("disabled");
        assertThat(reviewer.submitted).isEmpty();
    }

    /**
     * A sign-off says a named person looked. Owning the project is not being that person, and
     * letting the owner record it for them would make a formal session decorative.
     */
    @Test
    void youCanOnlySignOffAsYourself() {
        controller.addParticipant(alice, project.id(), 7,
                new MergeRequestController.ParticipantUpsert(
                        "someone-else", SessionParticipant.Kind.USER, "reviewer", true));

        assertThatThrownBy(() -> controller.signOff(alice, project.id(), 7, "someone-else"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(store.outstandingSignOffs(project.id(), 7)).isEqualTo(1);
    }

    /** mrr cannot authenticate a forge account, so it will not record a sign-off for one. */
    @Test
    void aForgeParticipantCannotBeSignedOffThroughMrr() {
        controller.addParticipant(alice, project.id(), 7,
                new MergeRequestController.ParticipantUpsert(
                        "ada", SessionParticipant.Kind.FORGE, "reviewer", true));

        assertThatThrownBy(() -> controller.signOff(alice, project.id(), 7, "ada"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot authenticate");
    }

    @Test
    void signingOffAsYourselfWorksAndIsIdempotentlyRefusedTwice() {
        controller.addParticipant(alice, project.id(), 7,
                new MergeRequestController.ParticipantUpsert(
                        alice.userId(), SessionParticipant.Kind.USER, "reviewer", true));

        assertThat(controller.signOff(alice, project.id(), 7, alice.userId()).signedOffAt())
                .isNotNull();
        assertThat(store.outstandingSignOffs(project.id(), 7)).isZero();

        assertThatThrownBy(() -> controller.signOff(alice, project.id(), 7, alice.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status())
                        .isEqualTo(HttpStatus.CONFLICT));
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

        MergeRequest merged = controller.merge(alice, project.id(), 7);

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

        Discussion posted = controller.postThread(alice, project.id(), 7,
                new MergeRequestController.ThreadUpsert("Looks good."));

        assertThat(posted.discussionId()).isEqualTo("ov-1");
        assertThat(gitlab.posted).containsExactly("Looks good.");
        assertThat(gitlab.lastToken).isEqualTo("glpat-operator");
    }

    @Test
    void commitListUsesTheReviewToken() {
        gitlab.commits = List.of(new MrCommit("abc", "msg", "Ada", "t", "msg"));

        var commits = controller.commits(alice, project.id(), 7);

        assertThat(commits).hasSize(1);
        assertThat(gitlab.lastToken).isEqualTo("glpat-x");
    }

    @Test
    void listingFilesCarriesNoPatchBodies() {
        gitlab.commitPatches = List.of(
                new FilePatch("A.java", "@@\n+int x;"),
                new FilePatch("B.java", "@@\n+int y;"));

        assertThat(controller.commitFiles(alice, project.id(), 7, "abc"))
                .containsExactly("A.java", "B.java");
        assertThat(gitlab.patchRequests).isEmpty();
    }

    @Test
    void unveilingAFileFetchesThatFileAlone() {
        gitlab.commitPatches = List.of(
                new FilePatch("A.java", "@@\n+int x;"),
                new FilePatch("B.java", "@@\n+int y;"));

        FilePatch patch =
                controller.commitFilePatch(alice, project.id(), 7, "abc", "B.java");

        assertThat(patch.path()).isEqualTo("B.java");
        assertThat(patch.diff()).contains("+int y;").doesNotContain("+int x;");
        assertThat(gitlab.patchRequests).containsExactly("B.java");
    }

    @Test
    void unveilingAFileTheCommitDidNotTouchIs404() {
        gitlab.commitPatches = List.of(new FilePatch("A.java", "@@\n+int x;"));

        assertThatThrownBy(() -> controller.commitFilePatch(alice, project.id(), 7, "abc", "Z.java"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("not_found"));
    }

    private static Project withOperator(Project p, String token) {
        return new Project(p.id(), p.ownerId(), p.name(), p.enabled(), p.forge(), p.gitlabUrl(),
                p.gitlabProjectId(), p.gitlabToken(), token, p.webhookSecret(),
                p.llmProviderId(), p.llm(), p.skill(), p.skipDrafts(),
                p.agentMaxTurns(), p.agentTools(), p.autoApprove(), p.librarianEnabled(),
                p.memoryHeadSha(), p.memoryStatus(), p.memoryRefreshedAt(),
                p.lastErrorKind(), p.lastError(), p.lastErrorAt(), Instant.now());
    }
}
