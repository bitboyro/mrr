package ro.bitboy.mrr.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.RuntimeSettings;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.auth.Principal;
import ro.bitboy.mrr.forge.ForgeFactory;
import ro.bitboy.mrr.forge.MergeRequest;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentReviewsTest {

    @TempDir
    Path tempDir;

    private Store store;
    private FakeForge gitlab;
    private Project project;
    private Principal agent;
    private Principal ordinary;
    private AgentReviews reviews;
    private Reviewer reviewer;
    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = TestStores.persist(store, TestStores.project(alice.id(), "calc", "hook-secret"));
        String secret = store.createAgentKey(alice.id(), "cursor", List.of(project.id()));
        agent = store.resolveToken(secret).orElseThrow();
        ordinary = new Principal(alice.id(), alice.name(), alice.role());
        gitlab = new FakeForge();
        gitlab.oneMr = Optional.of(new MergeRequest(7, "Guard", "opened", "sha-1",
                "https://gitlab/mr/7", false));
        workers = Executors.newSingleThreadExecutor();
        reviewer = new Reviewer(store, ForgeFactory.fixed(gitlab), TestStores.properties(),
                new ObjectMapper(), new FakeMemory(), p -> {
            throw new AssertionError("the model must not be called");
        }, workers);
        reviews = new AgentReviews(store, ForgeFactory.fixed(gitlab), reviewer,
                RuntimeSettings.frozen(TestStores.properties()), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void anExternalReviewCannotBeOverwrittenByAModelRerun() {
        Review posted = reviews.submit(agent, project.id(), 7, submission("external-only"));
        assertThatThrownBy(() -> store.resetForRerun(posted.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot be rerun");
        assertThat(store.findReview(posted.id()).orElseThrow().state()).isEqualTo(Review.State.READY);
    }

    @Test
    void aReviewOfAnOldPacketCannotBeAttachedToANewCommit() {
        var request = new AgentReviews.Submission("stale", "Reviewed the old diff", List.of(), null, "sha-old");
        assertThatThrownBy(() -> reviews.submit(agent, project.id(), 7, request))
                .isInstanceOf(ApiException.class).hasMessageContaining("head commit changed");
        assertThat(gitlab.posted).isEmpty();
        assertThat(store.findAgentSubmission(project.id(), "stale")).isEmpty();
    }

    @Test
    void aSubmissionIdCannotReturnAReviewOfAnotherRequest() {
        reviews.submit(agent, project.id(), 7, submission("same-id"));
        assertThatThrownBy(() -> reviews.submit(agent, project.id(), 8, submission("same-id")))
                .isInstanceOf(ApiException.class).hasMessageContaining("another merge request");
    }

    @Test
    void reviewNowRefusesWhenAutoReviewIsOff() {
        store.updateProject(project.withAutoReview(false));
        project = store.findProject(project.id()).orElseThrow();
        Trigger trigger = new Trigger(store, ForgeFactory.fixed(gitlab), reviewer);

        assertThatThrownBy(() -> trigger.now(project, 7))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void anOrdinaryTokenCannotSubmit() {
        assertThatThrownBy(() -> reviews.submit(ordinary, project.id(), 7, submission("once")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        assertThat(gitlab.posted).isEmpty();
    }

    @Test
    void aSubmittedReviewIsAppendedAndDoesNotTakeTheModelSlot() {
        Review posted = reviews.submit(agent, project.id(), 7, submission("once"));

        assertThat(posted.state()).isEqualTo(Review.State.READY);
        assertThat(posted.source()).isEqualTo(Review.Source.AGENT);
        assertThat(posted.headSha()).isEqualTo("sha-1");
        assertThat(posted.submissionId()).isEqualTo("once");
        assertThat(gitlab.posted).anySatisfy(body -> {
            assertThat(body).startsWith("### Agent review");
            assertThat(body).contains("cursor");
            assertThat(body).doesNotContain("### mrr review");
        });
        assertThat(gitlab.posted).anySatisfy(body -> assertThat(body).contains("leaks the handle"));
        assertThat(store.findingsOfMr(project.id(), 7)).singleElement()
                .satisfies(f -> {
                    assertThat(f.origin()).isEqualTo(Finding.Origin.AGENT);
                    assertThat(f.title()).isEqualTo("leaks the handle");
                });
        assertThat(store.findOverview(project.id(), 7)).isEmpty();

        assertThat(store.claimReview(project.id(), 7, "sha-1", "{}", TestStores.mrInfo()).created())
                .isTrue();

        int posts = gitlab.posted.size();
        Review again = reviews.submit(agent, project.id(), 7, submission("once"));
        assertThat(again.id()).isEqualTo(posted.id());
        assertThat(gitlab.posted).hasSize(posts);
    }

    @Test
    void autoReviewOffStillPosts() {
        store.updateProject(project.withAutoReview(false));
        project = store.findProject(project.id()).orElseThrow();

        Review posted = reviews.submit(agent, project.id(), 7, submission("proxy"));

        assertThat(posted.state()).isEqualTo(Review.State.READY);
        assertThat(gitlab.posted).isNotEmpty();
    }

    @Test
    void anAddressedPriorClosesThatFindingOnly() {
        String modelId = store.claimReview(project.id(), 7, "sha-old", "{}", TestStores.mrInfo())
                .review().id();
        Finding open = store.createFinding(new Finding(Ids.newId(), modelId, project.id(), 7,
                "A.java", 3, Finding.Severity.MAJOR, "old bug", "body",
                "disc-old", "note-old", true, Finding.State.OPEN, Instant.now(), null));

        reviews.submit(agent, project.id(), 7, new AgentReviews.Submission(
                "fix", "the handle leak is new; the old bug is gone",
                List.of(new AgentReviews.FindingDraft("A.java", 9, "MAJOR", "leaks the handle", "close it")),
                List.of(new AgentReviews.PriorDraft(open.id(), true, "the null check landed")), "sha-1"));

        assertThat(store.findFinding(open.id()).orElseThrow().state()).isEqualTo(Finding.State.ADDRESSED);
        assertThat(gitlab.resolved).contains("disc-old:true");
    }

    @Test
    void packetCarriesTheDiffAndNotASecret() {
        AgentReviews.ReviewPacket packet = reviews.packet(agent, project.id(), 7);

        assertThat(packet.headSha()).isEqualTo("sha-1");
        assertThat(packet.diff()).contains("int x");
        assertThat(packet.diff()).doesNotContain(project.gitlabToken());
        assertThatThrownBy(() -> reviews.packet(ordinary, project.id(), 7))
                .isInstanceOf(ApiException.class);
    }

    private static AgentReviews.Submission submission(String id) {
        return new AgentReviews.Submission(id, "The change leaks a handle.",
                List.of(new AgentReviews.FindingDraft("A.java", 4, "MAJOR", "leaks the handle",
                        "close it")),
                null, "sha-1");
    }
}
