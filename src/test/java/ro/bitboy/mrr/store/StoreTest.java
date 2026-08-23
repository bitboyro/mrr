package ro.bitboy.mrr.store;

import ro.bitboy.mrr.secret.PlaintextSecretBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import ro.bitboy.mrr.MrrProperties;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.model.AgentDocument;
import ro.bitboy.mrr.model.AgentSkill;
import ro.bitboy.mrr.model.AgentTrace;
import ro.bitboy.mrr.model.FailureKind;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.GitlabInstance;
import ro.bitboy.mrr.model.InstanceStats;
import ro.bitboy.mrr.model.LibrarySkill;
import ro.bitboy.mrr.model.LlmProvider;
import ro.bitboy.mrr.model.MergeRequestInfo;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Reply;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.ReviewModel;
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
    private JdbcClient db;
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

        db = JdbcClient.create(dataSource);
        store = new Store(db, new PlaintextSecretBox());
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
        TestStores.persist(store, project(alice.id(), "alice-app"));
        TestStores.persist(store, project(bob.id(), "bob-app"));

        assertThat(store.listProjects(principal(alice)))
                .extracting(Project::name).containsExactly("alice-app");
        assertThat(store.listProjects(principal(admin)))
                .extracting(Project::name).containsExactlyInAnyOrder("alice-app", "bob-app");
    }

    @Test
    void aNonOwnerGets404NotForbiddenSoExistenceDoesNotLeak() {
        Project owned = TestStores.persist(store, project(alice.id(), "alice-app"));

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
    void operatorTokenIsStoredAndMaskedOnRead() {
        LlmProvider llm = TestStores.llmProvider(alice.id());
        Project created = TestStores.persist(store, new Project(Ids.newId(), alice.id(), "app", true,
                "https://gitlab.com", "g/app", "glpat-review", "glpat-operator-token", Ids.newSecret(),
                llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now()));

        Project stored = store.findProject(created.id()).orElseThrow();
        assertThat(stored.gitlabOperatorToken()).isEqualTo("glpat-operator-token");
        assertThat(stored.hasOperatorToken()).isTrue();
        assertThat(stored.redacted().gitlabOperatorToken()).isEqualTo("****oken").doesNotContain("glpat-operator");
        assertThat(stored.asOperator().gitlabToken()).isEqualTo("glpat-operator-token");
    }

    @Test
    void claimingTheSameHeadCommitTwiceReturnsTheOriginalReview() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));

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
    void updateMergeRequestInfoPersistsHowFarTheBranchIsBehind() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String id = store.claimReview(project.id(), 7, "abc123", "{}", mrInfo()).review().id();

        store.updateMergeRequestInfo(id, mrInfo().withGitMeta(
                4, "Ada Lovelace (@ada)", null, "need_rebase", 3, false));

        MergeRequestInfo mr = store.findReview(id).orElseThrow().mr();
        assertThat(mr.commitCount()).isEqualTo(4);
        assertThat(mr.detailedMergeStatus()).isEqualTo("need_rebase");
        assertThat(mr.divergedCommitsCount()).isEqualTo(3);
        assertThat(mr.hasConflicts()).isFalse();
        assertThat(mr.behindTarget()).isTrue();
    }

    @Test
    void reviewLifecycleReachesATerminalState() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
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
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
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
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String review = store.claimReview(project.id(), 5, "sha", "{}", mrInfo()).review().id();
        Finding finding = store.createFinding(finding(review, project.id(), 5, "disc-x"));

        AgentTrace trace = new AgentTrace(1, 1, List.of(
                AgentTrace.Event.assistant(1, 9L, "end_turn", "yes, that breaks", List.of())));
        Reply saved = store.createReply(new Reply(Ids.newId(), project.id(), 5, "disc-x",
                finding.id(), "gl-note-1", "sys\n\n---\n\nuser", "yes, that breaks", trace, Instant.now()));

        assertThat(store.listReplies(project.id(), 5)).singleElement()
                .extracting(Reply::id, Reply::discussionId).containsExactly(saved.id(), "disc-x");
        assertThat(store.findReply(saved.id())).get()
                .extracting(Reply::prompt, r -> r.trace().turns())
                .containsExactly("sys\n\n---\n\nuser", 1);
        assertThat(store.findReply(saved.id()).orElseThrow().summary().prompt()).isNull();
        assertThat(store.findReplyBySourceNote(project.id(), "disc-x", "gl-note-1")).isPresent();
    }

    /**
     * The model call is the expensive, non-repeatable step. If delivery to GitLab then
     * fails, the review we already paid for has to survive.
     */
    @Test
    void aResponseSurvivesAFailureToPostIt() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
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
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
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
    void closeMrFollowsGitLabWithoutClearingFindings() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String review = store.claimReview(project.id(), 15, "sha-15", "{}", mrInfo()).review().id();
        store.createFinding(finding(review, project.id(), 15, "disc-open"));
        store.upsertMrStatus(project.id(), 15, MrStatus.Status.NEEDS_WORK, review, "sha-15", 1);

        store.closeMr(project.id(), 15, MrStatus.Status.MERGED);

        assertThat(store.findMrStatus(project.id(), 15)).get()
                .extracting(MrStatus::currentStatus, MrStatus::openFindings)
                .containsExactly(MrStatus.Status.MERGED, 1);
        assertThat(store.openFindings(project.id(), 15)).hasSize(1);
        assertThat(store.listActiveMrStatus(project.id())).isEmpty();
    }

    @Test
    void listsMergeRequestsNewestFirstWithFilters() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String first = store.claimReview(project.id(), 1, "sha-1", "{}", mrInfo()).review().id();
        String tenth = store.claimReview(project.id(), 10, "sha-10", "{}", mrInfo()).review().id();
        String third = store.claimReview(project.id(), 3, "sha-3", "{}", mrInfo()).review().id();
        store.upsertMrStatus(project.id(), 1, MrStatus.Status.CLEAN, first, "sha-1", 0);
        store.upsertMrStatus(project.id(), 10, MrStatus.Status.NEEDS_WORK, tenth, "sha-10", 1);
        store.upsertMrStatus(project.id(), 3, MrStatus.Status.MERGED, third, "sha-3", 0);

        MrStatus.Page firstPage = store.listMrStatus(project.id(), null, null, 0, 2);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(MrStatus::mrIid).containsExactly(10L, 3L);

        MrStatus.Page rest = store.listMrStatus(project.id(), null, null, 2, 2);
        assertThat(rest.items()).extracting(MrStatus::mrIid).containsExactly(1L);

        MrStatus.Page needs = store.listMrStatus(project.id(), "NEEDS_WORK", null, 0, 10);
        assertThat(needs.total()).isEqualTo(1);
        assertThat(needs.items().getFirst().title()).contains("Guard");

        assertThat(store.listMrStatus(project.id(), null, "10", 0, 10).items())
                .extracting(MrStatus::mrIid).containsExactly(10L);
        assertThat(store.listMrStatus(project.id(), null, "!3", 0, 10).items())
                .extracting(MrStatus::mrIid).containsExactly(3L);
    }

    @Test
    void openFindingsSpanEveryReviewOfTheMergeRequest() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
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
    void theLatestOverviewIsThePreviousReadyReviewOnTheSameMr() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String first = store.claimReview(project.id(), 10, "sha-1", "{}", mrInfo()).review().id();
        String second = store.claimReview(project.id(), 10, "sha-2", "{}", mrInfo()).review().id();
        String third = store.claimReview(project.id(), 10, "sha-3", "{}", mrInfo()).review().id();
        store.markReady(first, "overview-1", "note-1");
        store.markReady(second, "overview-2", "note-2");

        assertThat(store.findLatestOverview(project.id(), 10, third)).get()
                .extracting(Review::id, Review::discussionId)
                .containsExactly(second, "overview-2");
        assertThat(store.overviewDiscussionIds(project.id(), 10))
                .containsExactly("overview-1", "overview-2");
        assertThat(store.findLatestOverview(project.id(), 11, third)).isEmpty();
    }

    @Test
    void overviewThreadIsStoredOnTheMergeRequest() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String first = store.claimReview(project.id(), 12, "sha-1", "{}", mrInfo()).review().id();
        store.upsertMrStatus(project.id(), 12, MrStatus.Status.CLEAN, first, "sha-1", 0);
        store.saveOverview(project.id(), 12, "ov-live", "note-live");
        store.markReady(first, "ov-old", "note-old");

        assertThat(store.findOverview(project.id(), 12)).get()
                .extracting(Store.Overview::discussionId, Store.Overview::noteId)
                .containsExactly("ov-live", "note-live");

        store.saveOverview(project.id(), 12, "ov-live", "note-live");
        store.upsertMrStatus(project.id(), 12, MrStatus.Status.NEEDS_WORK, first, "sha-1", 1);
        assertThat(store.findOverview(project.id(), 12)).get()
                .extracting(Store.Overview::discussionId)
                .isEqualTo("ov-live");
    }

    @Test
    void findsTheOwningFindingForAGitLabThread() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String review = store.claimReview(project.id(), 5, "sha", "{}", mrInfo()).review().id();
        store.createFinding(finding(review, project.id(), 5, "disc-x"));

        assertThat(store.findByDiscussion(project.id(), 5, "disc-x")).isPresent();
        // A thread mrr never opened is somebody else's conversation.
        assertThat(store.findByDiscussion(project.id(), 5, "not-ours")).isEmpty();
    }

    @Test
    void mrStatusTracksOpenFindingsAndMrrApproval() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
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
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        store.createSkill(project.id(), "security", "No eval.", true);
        store.createSkill(project.id(), "style", "No wildcards.", false);

        assertThat(store.listSkills(project.id()))
                .extracting(AgentSkill::name, AgentSkill::enabled)
                .containsExactly(tuple("security", true), tuple("style", false));

        AgentSkill style = store.listSkills(project.id()).get(1);
        store.updateSkill(style.id(), null, null, true, null, null);
        assertThat(store.findSkill(project.id(), style.id())).get()
                .extracting(AgentSkill::enabled).isEqualTo(true);

        assertThatThrownBy(() -> store.createSkill(project.id(), "security", "dup", true))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aSkillCanBeScopedToASourceBranchPattern() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        AgentSkill hotfix = store.createSkill(project.id(), "hotfix", "Be strict.", true, "hotfix/*");
        assertThat(hotfix.branchPattern()).isEqualTo("hotfix/*");
        assertThat(hotfix.appliesTo("hotfix/prod")).isTrue();
        assertThat(hotfix.appliesTo("feature/x")).isFalse();

        store.updateSkill(hotfix.id(), null, null, null, null, "release-\\d+");
        assertThat(store.findSkill(project.id(), hotfix.id())).get()
                .extracting(AgentSkill::branchPattern).isEqualTo("release-\\d+");

        store.updateSkill(hotfix.id(), null, null, null, null, "");
        assertThat(store.findSkill(project.id(), hotfix.id())).get()
                .extracting(AgentSkill::branchPattern).isNull();

        assertThatThrownBy(() -> store.createSkill(project.id(), "bad", "x", true, "["))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void userContextOnAnMrSurvivesStatusUpserts() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        String id = store.claimReview(project.id(), 11, "sha-ctx", "{}", mrInfo()).review().id();
        store.upsertMrStatus(project.id(), 11, MrStatus.Status.REVIEWING, id, "sha-ctx", 0);

        store.setMrContext(project.id(), 11, "Implements JIRA-12. Flag is off.");
        store.upsertMrStatus(project.id(), 11, MrStatus.Status.CLEAN, id, "sha-ctx", 0);

        assertThat(store.findMrStatus(project.id(), 11)).get()
                .extracting(MrStatus::userContext, MrStatus::currentStatus)
                .containsExactly("Implements JIRA-12. Flag is off.", MrStatus.Status.CLEAN);

        store.setMrContext(project.id(), 11, "  ");
        assertThat(store.findMrStatus(project.id(), 11)).get()
                .extracting(MrStatus::userContext).isNull();
    }

    @Test
    void documentsAreANamedFolderPerProject() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
        store.createDocument(project.id(), "architecture.md", "Hexagonal.", true);
        assertThat(store.listDocuments(project.id()))
                .extracting(AgentDocument::name)
                .containsExactly("architecture.md");
        store.deleteDocument(project.id(), store.listDocuments(project.id()).getFirst().id());
        assertThat(store.listDocuments(project.id())).isEmpty();
    }

    @Test
    void aFailedReviewIsRecordedOnTheProjectForTheUi() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-app"));
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

    @Test
    void creatingAProjectSkillWritesTheOwnerLibraryAndAttachReusesIt() {
        Project one = TestStores.persist(store, project(alice.id(), "one"));
        Project two = TestStores.persist(store, project(alice.id(), "two"));
        AgentSkill first = store.createSkill(one.id(), "security", "No eval.", true);
        assertThat(first.libraryId()).isNotBlank();
        assertThat(store.listLibrary(principal(alice)))
                .extracting(LibrarySkill::name, LibrarySkill::body)
                .containsExactly(tuple("security", "No eval."));

        AgentSkill attached = store.attachSkill(two.id(), first.libraryId(), true, null);
        assertThat(attached.libraryId()).isEqualTo(first.libraryId());
        assertThat(store.listSkills(two.id())).extracting(AgentSkill::name).containsExactly("security");

        store.updateSkill(first.id(), null, "Still no eval.", null, null, null);
        assertThat(store.listSkills(two.id()).getFirst().body()).isEqualTo("Still no eval.");

        assertThatThrownBy(() -> store.createSkill(one.id(), "security", "dup", true))
                .isInstanceOf(ApiException.class);

        store.deleteSkill(one.id(), first.id());
        assertThat(store.listLibrary(principal(alice))).hasSize(1);
        assertThatThrownBy(() -> store.deleteLibrary(first.libraryId()))
                .isInstanceOf(ApiException.class);
        store.deleteSkill(two.id(), attached.id());
        store.deleteLibrary(first.libraryId());
        assertThat(store.listLibrary(principal(alice))).isEmpty();
    }

    @Test
    void aUserDoesNotSeeAnotherUsersLibraryAndAdminSeesAll() {
        Project aliceApp = TestStores.persist(store, project(alice.id(), "alice-app"));
        Project bobApp = TestStores.persist(store, project(bob.id(), "bob-app"));
        store.createSkill(aliceApp.id(), "security", "Alice rules.", true);
        store.createSkill(bobApp.id(), "security", "Bob rules.", true);

        assertThat(store.listLibrary(principal(alice)))
                .extracting(LibrarySkill::body).containsExactly("Alice rules.");
        assertThat(store.listLibrary(principal(bob)))
                .extracting(LibrarySkill::body).containsExactly("Bob rules.");
        assertThat(store.listLibrary(principal(admin))).hasSize(2);
        assertThatThrownBy(() -> store.requireLibraryVisible(principal(bob),
                store.listLibrary(principal(alice)).getFirst().id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void skillLibraryBackfillLinksExistingProjectSkills() {
        Project project = TestStores.persist(store, project(alice.id(), "legacy"));
        String skillId = Ids.newId();
        db.sql("""
                INSERT INTO agent_skills (id, project_id, name, body, enabled, position, created_at)
                VALUES (?, ?, ?, ?, 1, 0, ?)
                """).params(skillId, project.id(), "reviewer", "Be careful.", Instant.now().toString())
                .update();

        new SchemaPatch(db).patch();

        AgentSkill skill = store.findSkill(project.id(), skillId).orElseThrow();
        assertThat(skill.libraryId()).isNotBlank();
        assertThat(skill.body()).isEqualTo("Be careful.");
        assertThat(store.listLibrary(principal(alice)))
                .extracting(LibrarySkill::name, LibrarySkill::body)
                .contains(tuple("reviewer", "Be careful."));
    }

    @Test
    void settingsOverlayClampsAndStatsCountMrsAndTokens() {
        RuntimeSettings runtime = new RuntimeSettings(new MrrProperties(
                "/tmp/root-token.txt", "http://localhost:8130", "/tmp/memory",
                new MrrProperties.Review(2, 20_000, 30, 8_000, 8_000, 8_000, 2, 8_000, 8)), store);
        runtime.load();
        runtime.save(java.util.Map.of(
                RuntimeSettings.WORKERS, "0",
                RuntimeSettings.MAX_MODEL_TOKENS, "32000",
                RuntimeSettings.ANTHROPIC_EFFORT, "LOW"));
        assertThat(runtime.review().workers()).isEqualTo(2);
        assertThat(runtime.review().maxModelTokens()).isEqualTo(32_000);
        assertThat(runtime.review().anthropicEffort()).isEqualTo("low");
        assertThat(runtime.sources().get(RuntimeSettings.WORKERS)).isEqualTo(RuntimeSettings.SOURCE_SAVED);

        assertThatThrownBy(() -> runtime.save(java.util.Map.of(RuntimeSettings.BASE_URL, "not-a-url")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("baseUrl");
                });
        assertThat(runtime.baseUrl()).isEqualTo("http://localhost:8130");
        runtime.save(java.util.Map.of(RuntimeSettings.BASE_URL, "https://mrr.example.com/"));
        assertThat(runtime.baseUrl()).isEqualTo("https://mrr.example.com");
        assertThat(runtime.webhookUrl("proj-1"))
                .isEqualTo("https://mrr.example.com/webhooks/gitlab/proj-1");

        Project project = TestStores.persist(store, project(alice.id(), "stats-app"));
        String one = store.claimReview(project.id(), 1, "sha-1", "{}", mrInfo()).review().id();
        String two = store.claimReview(project.id(), 2, "sha-2", "{}", mrInfo()).review().id();
        store.markReady(one, "d", "n");
        store.markFailed(two, FailureKind.PROVIDER, "nope");
        store.saveReviewUsage(one, new ReviewModel.Usage(10, 4, 2));
        store.saveReviewUsage(two, new ReviewModel.Usage(3, 1, 0));
        store.createFinding(finding(one, project.id(), 1, "disc-1"));

        InstanceStats stats = store.instanceStats(1);
        assertThat(stats.mrsProcessed()).isEqualTo(2);
        assertThat(stats.reviewsTotal()).isEqualTo(2);
        assertThat(stats.inFlight()).isEqualTo(1);
        assertThat(stats.tokens().input()).isEqualTo(13);
        assertThat(stats.tokens().output()).isEqualTo(5);
        assertThat(stats.tokens().cacheRead()).isEqualTo(2);
        assertThat(stats.findingsOpen()).isEqualTo(1);
        assertThat(stats.failuresByKind()).containsEntry(FailureKind.PROVIDER.name(), 1L);
        assertThat(stats.projects()).anySatisfy(row -> {
            assertThat(row.name()).isEqualTo("stats-app");
            assertThat(row.mrs()).isEqualTo(2);
            assertThat(row.inputTokens()).isEqualTo(13);
        });
    }

    @Test
    void gitlabInstancesAreNamedUrlsWithNoToken() {
        GitlabInstance gitlab = store.createGitlabInstance(adminOf(alice), true, " GitLab.com ", "https://gitlab.com/");
        assertThat(gitlab.name()).isEqualTo("GitLab.com");
        assertThat(gitlab.url()).isEqualTo("https://gitlab.com");

        GitlabInstance self = store.createGitlabInstance(adminOf(alice), true, "corp", "https://git.example.com");
        store.updateGitlabInstance(adminOf(alice), self.id(), "Corp GitLab", "https://git.example.com/gitlab/");
        assertThat(store.findGitlabInstance(adminOf(alice), self.id())).get()
                .extracting(GitlabInstance::name, GitlabInstance::url)
                .containsExactly("Corp GitLab", "https://git.example.com/gitlab");

        assertThat(store.listGitlabInstances(adminOf(alice)))
                .extracting(GitlabInstance::name)
                .containsExactly("Corp GitLab", "GitLab.com");

        assertThatThrownBy(() -> store.createGitlabInstance(adminOf(alice), true, "GitLab.com", "https://gitlab.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        assertThatThrownBy(() -> store.createGitlabInstance(adminOf(alice), true, "bad", "gitlab.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> store.updateGitlabInstance(adminOf(alice), "missing", "x", "https://gitlab.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));

        store.deleteGitlabInstance(adminOf(alice), gitlab.id());
        assertThat(store.findGitlabInstance(adminOf(alice), gitlab.id())).isEmpty();
        assertThatThrownBy(() -> store.deleteGitlabInstance(adminOf(alice), gitlab.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void llmProvidersStoreEndpointNotKeyAndBlankBaseUrlClears() {
        LlmProvider opus = store.createLlmProvider(adminOf(alice), false, "Opus", Project.Provider.ANTHROPIC,
                "claude-opus-5", "  ", "sk-x");
        assertThat(opus.modelBaseUrl()).isNull();
        assertThat(opus.provider()).isEqualTo(Project.Provider.ANTHROPIC);

        LlmProvider ollama = store.createLlmProvider(adminOf(alice), false, "Ollama", Project.Provider.OPENAI_COMPAT,
                "llama3.2", "http://ollama:11434/v1/", "sk-x");
        assertThat(ollama.modelBaseUrl()).isEqualTo("http://ollama:11434/v1");

        store.updateLlmProvider(adminOf(alice), ollama.id(), null, null, "llama3.3", "", null);
        LlmProvider cleared = store.findLlmProvider(adminOf(alice), ollama.id()).orElseThrow();
        assertThat(cleared.model()).isEqualTo("llama3.3");
        assertThat(cleared.modelBaseUrl()).isNull();

        assertThatThrownBy(() -> store.createLlmProvider(adminOf(alice), false, "Opus", Project.Provider.ANTHROPIC,
                "claude-sonnet-4", null, "sk-x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        assertThatThrownBy(() -> store.createLlmProvider(adminOf(alice), false, "bad", Project.Provider.OPENAI_COMPAT,
                "gpt-4", "not-a-url", "sk-x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> store.createLlmProvider(adminOf(alice), false, "no-model", Project.Provider.ANTHROPIC,
                "  ", null, "sk-x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));

        store.deleteLlmProvider(adminOf(alice), opus.id());
        assertThat(store.listLlmProviders(adminOf(alice))).extracting(LlmProvider::name).containsExactly("Ollama");
    }

    @Test
    void schemaPatchCreatesCatalogTablesOnExistingDatabases() {
        db.sql("DROP TABLE IF EXISTS gitlab_instances").update();
        db.sql("DROP TABLE IF EXISTS llm_providers").update();
        new SchemaPatch(db).patch();
        GitlabInstance row = store.createGitlabInstance(adminOf(alice), true, "patched", "https://gitlab.example");
        assertThat(store.findGitlabInstance(adminOf(alice), row.id())).isPresent();
    }

    @Test
    void theGitlabUrlIsCopiedButTheLlmEntryIsFollowed() {
        GitlabInstance gitlab = store.createGitlabInstance(adminOf(alice), true, "gitlab.com", "https://gitlab.com");
        LlmProvider opus = store.createLlmProvider(adminOf(alice), false, "Opus",
                Project.Provider.ANTHROPIC, "claude-opus-5", null, "sk-x");
        Project project = store.createProject(new Project(Ids.newId(), alice.id(), "calc", true,
                gitlab.url(), "group/calc", "glpat-x", null, Ids.newSecret(),
                opus.id(), opus,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now()));

        store.updateGitlabInstance(adminOf(alice), gitlab.id(), "GitLab", "https://gitlab.example.com");
        store.updateLlmProvider(adminOf(alice), opus.id(), "Opus 4", Project.Provider.ANTHROPIC,
                "claude-opus-4", null, null);

        Project kept = store.findProject(project.id()).orElseThrow();
        assertThat(kept.gitlabUrl()).isEqualTo("https://gitlab.com");  // copied at registration
        assertThat(kept.model()).isEqualTo("claude-opus-4");           // referenced, so it moves
    }

    @Test
    void anEntryCannotBeDeletedWhileAProjectReferencesIt() {
        LlmProvider entry = store.createLlmProvider(adminOf(alice), false, "In use",
                Project.Provider.ANTHROPIC, "claude-opus-5", null, "sk-x");
        Project project = store.createProject(new Project(Ids.newId(), alice.id(), "calc", true,
                "https://gitlab.com", "group/calc", "glpat-x", null, Ids.newSecret(),
                entry.id(), entry,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null,
                Instant.now()));

        assertThatThrownBy(() -> store.deleteLlmProvider(adminOf(alice), entry.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("1 project(s) still use this entry");
                });

        store.deleteProject(project.id());
        store.deleteLlmProvider(adminOf(alice), entry.id());
        assertThat(store.findLlmProvider(adminOf(alice), entry.id())).isEmpty();
    }

    @Test
    void schemaInitSurvivesAnOlderRepliesTableThenPatchAddsSourceNoteId() {
        var dataSource = new SimpleDriverDataSource(
                new org.sqlite.JDBC(), "jdbc:sqlite:" + tempDir.resolve("old-replies.db"));
        JdbcClient old = JdbcClient.create(dataSource);
        old.sql("""
                CREATE TABLE replies (
                    id TEXT PRIMARY KEY, project_id TEXT NOT NULL, mr_iid INTEGER NOT NULL,
                    discussion_id TEXT NOT NULL, finding_id TEXT, prompt TEXT, response TEXT,
                    trace TEXT, created_at TEXT NOT NULL)
                """).update();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        new SchemaPatch(old).patch();
        List<String> columns = old.sql("PRAGMA table_info(replies)")
                .query((rs, n) -> rs.getString("name")).list();
        assertThat(columns).contains("source_note_id", "posted");
        List<String> indexes = old.sql("PRAGMA index_list(replies)")
                .query((rs, n) -> rs.getString("name")).list();
        assertThat(indexes).contains("idx_replies_source_note");
    }

    /**
     * An approval closes the finding and names the approver. It must not overwrite an
     * ADDRESSED one: that row's resolved_at is when a commit fixed it, which is the
     * time-to-fix measurement.
     */
    @Test
    void approvingRecordsTheUserAndLeavesAddressedFindingsAlone() {
        Project project = TestStores.persist(store, project(alice.id(), "alice-approve"));
        String reviewId = store.claimReview(project.id(), 9, "sha-1", "{}", mrInfo()).review().id();
        Finding open = store.createFinding(finding(reviewId, project.id(), 9, "disc-a"));
        Finding fixed = store.createFinding(finding(reviewId, project.id(), 9, "disc-b"));
        store.markAddressed(fixed.id());
        Instant fixedAt = store.findingsOf(reviewId).stream()
                .filter(f -> f.id().equals(fixed.id())).findFirst().orElseThrow().resolvedAt();

        store.markApproved(open.id(), "romanbotnari");
        store.markApproved(fixed.id(), "romanbotnari");

        assertThat(store.openFindings(project.id(), 9)).isEmpty();
        assertThat(store.findByDiscussion(project.id(), 9, "disc-a")).get()
                .satisfies(f -> {
                    assertThat(f.state()).isEqualTo(Finding.State.APPROVED);
                    assertThat(f.approvedBy()).isEqualTo("romanbotnari");
                    assertThat(f.resolvedAt()).isNotNull();
                });
        assertThat(store.findByDiscussion(project.id(), 9, "disc-b")).get()
                .satisfies(f -> {
                    assertThat(f.state()).isEqualTo(Finding.State.ADDRESSED);
                    assertThat(f.approvedBy()).isNull();
                    assertThat(f.resolvedAt()).isEqualTo(fixedAt);
                });
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
                Instant.parse("2026-08-14T07:15:30Z"), "[\"backend\"]", 3, null, null, null,
                null, null, null);
    }

    private Project project(String ownerId, String name) {
        LlmProvider llm = TestStores.llmProvider(ownerId);
        return new Project(Ids.newId(), ownerId, name, true,
                "https://gitlab.com", "group/" + name, "glpat-x", null, Ids.newSecret(),
                llm.id(), llm,
                null, false, 1, List.of(), false, false, null, null, null, null, null, null, Instant.now());
    }

    /** The catalogs are owner-scoped now; these tests curate shared entries as an admin. */
    private static Principal adminOf(ro.bitboy.mrr.model.User user) {
        return new Principal(user.id(), user.name(), ro.bitboy.mrr.model.Role.ADMIN);
    }

}
