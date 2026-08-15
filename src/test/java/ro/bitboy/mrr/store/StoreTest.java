package ro.bitboy.mrr.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Exercises the rules that would be expensive to get wrong: token identity and ownership. */
class StoreTest {

    private Store store;
    private User admin;
    private User alice;
    private User bob;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // A temp file, not ":memory:" — SimpleDriverDataSource opens a fresh connection per
        // call, and each one would get its own empty in-memory database.
        var dataSource = new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + tempDir.resolve("mrr-test.db"));

        // The same schema.sql and the same runner Spring Boot uses at boot, so the tests
        // fail if the production schema does.
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        store = new Store(JdbcClient.create(dataSource));
        admin = store.createUser("admin", Role.ADMIN);
        alice = store.createUser("alice", Role.USER);
        bob = store.createUser("bob", Role.USER);
    }

    @Test
    void resolvesAPresentedTokenToItsOwner() {
        String token = store.createToken(alice.id(), "cli");

        assertThat(store.resolveToken(token))
                .get()
                .extracting(Principal::userId, Principal::role)
                .containsExactly(alice.id(), Role.USER);
    }

    @Test
    void storesOnlyTheHashOfAToken() {
        String token = store.createToken(alice.id(), "cli");

        // The secret must not be recoverable from the row — only its prefix is.
        assertThat(store.listTokens(alice.id()))
                .singleElement()
                .satisfies(info -> {
                    assertThat(info.prefix()).isEqualTo(Ids.prefixOf(token));
                    assertThat(token).doesNotContain(info.prefix() + "-stored");
                });
        assertThat(store.resolveToken("mrr_not-a-real-token")).isEmpty();
    }

    @Test
    void revokingATokenIsScopedToItsOwner() {
        String aliceToken = store.createToken(alice.id(), "cli");
        String tokenId = store.listTokens(alice.id()).getFirst().id();

        assertThat(store.deleteToken(tokenId, bob.id())).isFalse();
        assertThat(store.resolveToken(aliceToken)).isPresent();

        assertThat(store.deleteToken(tokenId, alice.id())).isTrue();
        assertThat(store.resolveToken(aliceToken)).isEmpty();
    }

    @Test
    void aUserSeesOnlyItsOwnProjectsAndAnAdminSeesAll() {
        store.createProject(project(alice.id(), "alice-app"));
        store.createProject(project(bob.id(), "bob-app"));

        assertThat(store.listProjects(principal(alice)))
                .extracting(Project::name).containsExactly("alice-app");
        assertThat(store.listProjects(principal(admin)))
                .extracting(Project::name).containsExactlyInAnyOrder("alice-app", "bob-app");
    }

    @Test
    void aNonOwnerGets404NotForbiddenSoExistenceDoesNotLeak() {
        Project owned = store.createProject(project(alice.id(), "alice-app"));

        assertThatThrownBy(() -> store.requireVisible(principal(bob), owned.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));

        // ...and the same 404 for an id that genuinely does not exist.
        assertThatThrownBy(() -> store.requireVisible(principal(bob), "no-such-project"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));

        assertThat(store.requireVisible(principal(admin), owned.id()).id()).isEqualTo(owned.id());
    }

    @Test
    void claimingTheSameHeadCommitTwiceReturnsTheOriginalReview() {
        Project project = store.createProject(project(alice.id(), "alice-app"));

        Store.Claim first = store.claimReview(project.id(), 7, "abc123", "{}", mrInfo());
        Store.Claim redelivered = store.claimReview(project.id(), 7, "abc123", "{}", mrInfo());

        assertThat(first.created()).isTrue();
        assertThat(redelivered.created()).isFalse();
        assertThat(redelivered.review().id()).isEqualTo(first.review().id());

        // A new push is a new SHA, and therefore a new review.
        Store.Claim afterPush = store.claimReview(project.id(), 7, "def456", "{}", mrInfo());
        assertThat(afterPush.created()).isTrue();
        assertThat(afterPush.review().id()).isNotEqualTo(first.review().id());

        assertThat(store.listReviews(principal(alice), project.id(), 10)).hasSize(2);
    }

    @Test
    void reviewLifecycleReachesATerminalState() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String id = store.claimReview(project.id(), 1, "sha", "{}", mrInfo()).review().id();

        store.markProcessing(id, "prompt");
        assertThat(store.findReview(id)).get()
                .extracting(Review::state).isEqualTo(Review.State.PROCESSING);

        store.saveResponse(id, "looks good");
        store.markReady(id, "disc-1", "note-1");
        Review ready = store.findReview(id).orElseThrow();
        assertThat(ready.state()).isEqualTo(Review.State.READY);
        assertThat(ready.state().isTerminal()).isTrue();
        assertThat(ready.noteId()).isEqualTo("note-1");
        assertThat(ready.discussionId()).isEqualTo("disc-1");
        assertThat(ready.finishedAt()).isNotNull();

        // The list view must not carry the bulky columns, but keeps the turn count.
        assertThat(ready.summary().response()).isNull();
        assertThat(ready.summary().trace()).isNull();
        assertThat(ready.summary().agentTurns()).isEqualTo(ready.agentTurns());
    }

    @Test
    void traceRoundTripsAndIsDroppedFromTheListView() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String id = store.claimReview(project.id(), 1, "sha-trace", "{}", mrInfo()).review().id();

        var event = AgentTrace.Event.assistant(1, 12L, "end_turn", "verdict", List.of());
        AgentTrace trace = new AgentTrace(1, 8, List.of(event));
        store.markProcessing(id, "prompt");
        store.saveTrace(id, trace);

        Review stored = store.findReview(id).orElseThrow();
        assertThat(stored.prompt()).isEqualTo("prompt");
        assertThat(stored.agentTurns()).isEqualTo(1);
        assertThat(stored.trace().turns()).isEqualTo(1);
        assertThat(stored.trace().events()).singleElement()
                .extracting(AgentTrace.Event::stopReason).isEqualTo("end_turn");
        assertThat(stored.summary().trace()).isNull();
        assertThat(stored.summary().agentTurns()).isEqualTo(1);
    }

    @Test
    void repliesAreStoredPerMergeRequest() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String review = store.claimReview(project.id(), 5, "sha", "{}", mrInfo()).review().id();
        Finding finding = store.createFinding(finding(review, project.id(), 5, "disc-x"));

        AgentTrace trace = new AgentTrace(1, 1, List.of(
                AgentTrace.Event.assistant(1, 9L, "end_turn", "yes, that breaks", List.of())));
        Reply saved = store.createReply(new Reply(Ids.newId(), project.id(), 5, "disc-x",
                finding.id(), "sys\n\n---\n\nuser", "yes, that breaks", trace, Instant.now()));

        assertThat(store.listReplies(project.id(), 5)).singleElement()
                .extracting(Reply::id, Reply::discussionId).containsExactly(saved.id(), "disc-x");
        assertThat(store.findReply(saved.id())).get()
                .extracting(Reply::prompt, r -> r.trace().turns())
                .containsExactly("sys\n\n---\n\nuser", 1);
        assertThat(store.findReply(saved.id()).orElseThrow().summary().prompt()).isNull();
    }

    /**
     * The model call is the expensive, non-repeatable step. If delivery to GitLab then
     * fails, the review we already paid for has to survive.
     */
    @Test
    void aResponseSurvivesAFailureToPostIt() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String id = store.claimReview(project.id(), 2, "sha2", "{}", mrInfo()).review().id();

        store.markProcessing(id, "prompt");
        store.saveResponse(id, "## review\n\nFound a null deref.");
        store.markFailed(id, "GitLab POST /discussions failed with 403 FORBIDDEN");

        Review failed = store.findReview(id).orElseThrow();
        assertThat(failed.state()).isEqualTo(Review.State.FAILED);
        assertThat(failed.error()).contains("403");
        assertThat(failed.response()).contains("Found a null deref.");
    }

    @Test
    void cancellingStopsOnlyReviewsThatAreStillRunning() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String running = store.claimReview(project.id(), 3, "sha-a", "{}", mrInfo()).review().id();
        String finished = store.claimReview(project.id(), 4, "sha-b", "{}", mrInfo()).review().id();
        store.markReady(finished, "disc", "note");

        assertThat(store.cancelRunning(project.id(), 3, "merged")).isTrue();
        assertThat(store.findReview(running)).get()
                .extracting(Review::state).isEqualTo(Review.State.CANCELLED);

        // A review that already delivered must not be rewritten by a later merge event.
        assertThat(store.cancelRunning(project.id(), 4, "merged")).isFalse();
        assertThat(store.findReview(finished)).get()
                .extracting(Review::state).isEqualTo(Review.State.READY);
    }

    @Test
    void openFindingsSpanEveryReviewOfTheMergeRequest() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String first = store.claimReview(project.id(), 9, "sha-1", "{}", mrInfo()).review().id();
        String second = store.claimReview(project.id(), 9, "sha-2", "{}", mrInfo()).review().id();

        Finding a = store.createFinding(finding(first, project.id(), 9, "disc-a"));
        store.createFinding(finding(second, project.id(), 9, "disc-b"));

        // The point of the denormalised project/mr columns: a fix in the second commit has to
        // be able to resolve a thread the first review opened.
        assertThat(store.openFindings(project.id(), 9)).hasSize(2);

        store.markAddressed(a.id());
        assertThat(store.openFindings(project.id(), 9)).singleElement()
                .satisfies(f -> assertThat(f.discussionId()).isEqualTo("disc-b"));
        assertThat(store.findingsOf(first)).singleElement()
                .satisfies(f -> {
                    assertThat(f.state()).isEqualTo(Finding.State.ADDRESSED);
                    assertThat(f.resolvedAt()).isNotNull();
                });
    }

    @Test
    void findsTheOwningFindingForAGitLabThread() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String review = store.claimReview(project.id(), 5, "sha", "{}", mrInfo()).review().id();
        store.createFinding(finding(review, project.id(), 5, "disc-x"));

        assertThat(store.findByDiscussion(project.id(), 5, "disc-x")).isPresent();
        // A thread mrr never opened is somebody else's conversation.
        assertThat(store.findByDiscussion(project.id(), 5, "not-ours")).isEmpty();
    }

    @Test
    void mrStatusTracksOpenFindingsAndMrrApproval() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String id = store.claimReview(project.id(), 11, "sha", "{}", mrInfo()).review().id();

        store.upsertMrStatus(project.id(), 11, MrStatus.Status.REVIEWING, id, "sha", 0);
        assertThat(store.findMrStatus(project.id(), 11)).get()
                .extracting(MrStatus::currentStatus, MrStatus::openFindings, MrStatus::mrrApproved)
                .containsExactly(MrStatus.Status.REVIEWING, 0, false);

        store.upsertMrStatus(project.id(), 11, MrStatus.Status.CLEAN, id, "sha", 0);
        store.markMrrApproved(project.id(), 11, "sha");
        assertThat(store.findMrStatus(project.id(), 11)).get()
                .extracting(MrStatus::currentStatus, MrStatus::mrrApproved, MrStatus::mrrApprovedSha)
                .containsExactly(MrStatus.Status.CLEAN, true, "sha");

        store.createFinding(finding(id, project.id(), 11, "disc-z"));
        store.upsertMrStatus(project.id(), 11, MrStatus.Status.NEEDS_WORK, id, "sha", 1);
        // Approval flag is not cleared by a status upsert — only clearMrrApproved does that.
        assertThat(store.findMrStatus(project.id(), 11)).get()
                .extracting(MrStatus::currentStatus, MrStatus::mrrApproved, MrStatus::openFindings)
                .containsExactly(MrStatus.Status.NEEDS_WORK, true, 1);

        store.clearMrrApproved(project.id(), 11);
        assertThat(store.findMrStatus(project.id(), 11)).get()
                .extracting(MrStatus::mrrApproved).isEqualTo(false);
    }

    @Test
    void skillsAreANamedEnabledSetPerProject() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        store.createSkill(project.id(), "security", "No eval.", true);
        store.createSkill(project.id(), "style", "No wildcards.", false);

        assertThat(store.listSkills(project.id()))
                .extracting(AgentSkill::name, AgentSkill::enabled)
                .containsExactly(tuple("security", true), tuple("style", false));

        AgentSkill style = store.listSkills(project.id()).get(1);
        store.updateSkill(style.id(), null, null, true, null);
        assertThat(store.findSkill(project.id(), style.id())).get()
                .extracting(AgentSkill::enabled).isEqualTo(true);

        assertThatThrownBy(() -> store.createSkill(project.id(), "security", "dup", true))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void documentsAreANamedFolderPerProject() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        store.createDocument(project.id(), "architecture.md", "Hexagonal.", true);
        assertThat(store.listDocuments(project.id()))
                .extracting(AgentDocument::name)
                .containsExactly("architecture.md");
        store.deleteDocument(project.id(), store.listDocuments(project.id()).getFirst().id());
        assertThat(store.listDocuments(project.id())).isEmpty();
    }

    @Test
    void aFailedReviewIsRecordedOnTheProjectForTheUi() {
        Project project = store.createProject(project(alice.id(), "alice-app"));
        String id = store.claimReview(project.id(), 12, "sha-fail", "{}", mrInfo()).review().id();
        store.markFailed(id, FailureKind.QUOTA, "out of credits");

        Review failed = store.findReview(id).orElseThrow();
        assertThat(failed.errorKind()).isEqualTo(FailureKind.QUOTA);
        assertThat(store.findProject(project.id())).get()
                .extracting(Project::lastErrorKind, Project::lastError)
                .containsExactly(FailureKind.QUOTA, "out of credits");

        store.clearProjectError(project.id());
        assertThat(store.findProject(project.id())).get()
                .extracting(Project::lastErrorKind).isNull();
    }

    private Finding finding(String reviewId, String projectId, long mrIid, String discussionId) {
        return new Finding(Ids.newId(), reviewId, projectId, mrIid, "A.java", 42,
                Finding.Severity.MAJOR, "title", "body", discussionId, "note-1", true,
                Finding.State.OPEN, Instant.now(), null);
    }

    private Principal principal(User user) {
        return new Principal(user.id(), user.name(), user.role());
    }

    private MergeRequestInfo mrInfo() {
        return new MergeRequestInfo("Guard against divide by zero", "https://gitlab/mr/7",
                "opened", "open", false, "fix/div-zero", "main",
                "Alan Turing", "alan", "Ada Lovelace", "ada",
                "Guard against divide by zero", "Grace Hopper", "grace@example.com",
                Instant.parse("2026-08-14T07:15:30Z"), "[\"backend\"]", 3);
    }

    private Project project(String ownerId, String name) {
        return new Project(Ids.newId(), ownerId, name, true,
                "https://gitlab.com", "group/" + name,                 "glpat-x", Ids.newSecret(),
                Project.Provider.ANTHROPIC, null, "claude-opus-5", "sk-x",
                null, false, 1, List.of(), false, null, null, null, Instant.now());
    }

}
