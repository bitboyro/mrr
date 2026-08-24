package ro.bitboy.mrr.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import ro.bitboy.mrr.FakeForge;
import ro.bitboy.mrr.FakeMemory;
import ro.bitboy.mrr.FakeReplier;
import ro.bitboy.mrr.FakeReviewer;
import ro.bitboy.mrr.TestStores;
import ro.bitboy.mrr.auth.Ids;
import ro.bitboy.mrr.model.Finding;
import ro.bitboy.mrr.model.MrStatus;
import ro.bitboy.mrr.model.Project;
import ro.bitboy.mrr.model.Review;
import ro.bitboy.mrr.model.Role;
import ro.bitboy.mrr.model.User;
import ro.bitboy.mrr.store.Store;
import ro.bitboy.mrr.web.ApiException;
import ro.bitboy.mrr.forge.ForgeFactory;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class WebhookControllerTest {

    private static final String SECRET = "hook-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Store store;
    private FakeReviewer reviewer;
    private FakeReplier replier;
    private FakeMemory memory;
    private FakeForge gitlab;
    private WebhookController webhooks;
    private Project project;

    @BeforeEach
    void setUp() {
        store = TestStores.sqlite(tempDir);
        User alice = store.createUser("alice", Role.USER);
        project = TestStores.persist(store, TestStores.project(alice.id(), "calc", SECRET));
        reviewer = new FakeReviewer();
        replier = new FakeReplier();
        memory = new FakeMemory();
        gitlab = new FakeForge();
        webhooks = new WebhookController(store, reviewer, replier, memory, ForgeFactory.fixed(gitlab), MAPPER);
    }

    @Test
    void rejectsABadWebhookToken() {
        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers("wrong"), mr("open", "sha-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void anOpenEventClaimsAndSubmits() {
        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), mr("open", "sha-open"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Review review = store.findReview(project.id(), 42, "sha-open").orElseThrow();
        assertThat(review.state()).isEqualTo(Review.State.PENDING);
        assertThat(reviewer.submitted).containsExactly(review.id());
        assertThat(store.findMrStatus(project.id(), 42).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.REVIEWING);
    }

    @Test
    void anUpdateWithTheSameShaIsIgnored() {
        store.claimReview(project.id(), 42, "sha-same", "{}", TestStores.mrInfo());

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), mr("update", "sha-same"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("no_new_commit");
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void mergeCancelsARunningReviewAndClosesTheCycle() {
        String id = store.claimReview(project.id(), 42, "sha-run", "{}", TestStores.mrInfo())
                .review().id();
        store.upsertMrStatus(project.id(), 42, MrStatus.Status.REVIEWING, id, "sha-run", 0);

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), mr("merge", "sha-run"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(store.findReview(id).orElseThrow().state()).isEqualTo(Review.State.CANCELLED);
        assertThat(store.findMrStatus(project.id(), 42).orElseThrow().currentStatus())
                .isEqualTo(MrStatus.Status.MERGED);
        assertThat(memory.closed).containsExactly(project.id() + "!42:merge");
        assertThat(reviewer.submitted).isEmpty();
    }

    @Test
    void aNoteOnAnMrrThreadQueuesAReply() {
        String reviewId = store.claimReview(project.id(), 42, "sha-n", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), note(false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replier.calls).containsExactly(
                new FakeReplier.Call(project.id(), 42, "disc-1", "9001", "why?", "ada"));
    }

    @Test
    void aSystemNoteIsIgnoredEvenOnAnOwnedThread() {
        String reviewId = store.claimReview(project.id(), 42, "sha-n", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), note(true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("system_note");
        assertThat(replier.calls).isEmpty();
    }

    @Test
    void aNoteFromTheTokenUserIsIgnored() {
        String reviewId = store.claimReview(project.id(), 42, "sha-n", "{}", TestStores.mrInfo())
                .review().id();
        store.createFinding(new Finding(Ids.newId(), reviewId, project.id(), 42,
                "A.java", 3, Finding.Severity.MAJOR, "title", "body",
                "disc-1", "note-1", true, Finding.State.OPEN, Instant.now(), null));

        ResponseEntity<?> response = webhooks.receive("gitlab", project.id(), headers(SECRET), note(false, "mrr-bot"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting("reason").isEqualTo("own_note");
        assertThat(replier.calls).isEmpty();
    }

    @Test
    void aPayloadWithoutIidIsRejected() {
        assertThatThrownBy(() -> webhooks.receive("gitlab", project.id(), headers(SECRET), """
                {"object_kind":"merge_request","object_attributes":{"action":"open","iid":0}}
                """))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── forge routing ────────────────────────────────────────────────────────────────

    @Test
    void theLegacyGitlabPathKeepsWorkingForever() {
        // Every hook already registered in a GitLab UI points at /webhooks/gitlab/{id}, and mrr
        // does not create hooks — so this URL can never be retired.
        ResponseEntity<?> response =
                webhooks.receive("gitlab", project.id(), headers(SECRET), mr("open", "sha-legacy"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void aPathNamingADifferentForgeIsRefusedWithSomethingToActOn() {
        // Not a rejected signature: routing a GitLab project's hooks at the GitHub path would
        // check the wrong header and 401 every delivery with nothing to point at.
        assertThatThrownBy(() ->
                webhooks.receive("github", project.id(), headers(SECRET), mr("open", "sha-x")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("registered on GITLAB")
                        .contains("/webhooks/gitlab/" + project.id()));
    }

    @Test
    void aPathNamingNoForgeMrrKnowsIsRejected() {
        assertThatThrownBy(() ->
                webhooks.receive("bitbucket", project.id(), headers(SECRET), mr("open", "sha-y")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    /**
     * Regression: an unknown forge slug used to be checked after the project lookup, so a real
     * project id answered 200 ("ignored") and a made-up one answered 404 — an unauthenticated
     * caller could use that difference to find out which project ids exist, exactly what the
     * 404-not-401 choice on a missing project exists to prevent. Both must now fail identically.
     */
    @Test
    void anUnknownForgeSlugFailsTheSameWayWhetherOrNotTheProjectExists() {
        Throwable realProject = catchThrowable(() ->
                webhooks.receive("bitbucket", project.id(), headers(SECRET), mr("open", "sha-y")));
        Throwable madeUpProject = catchThrowable(() ->
                webhooks.receive("bitbucket", "not-a-real-id", headers(SECRET), mr("open", "sha-y")));

        assertThat(realProject).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThat(madeUpProject).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    /** The one header GitLab authenticates with, as it arrives on a real delivery. */
    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.add("X-Gitlab-Token", token);
        }
        return headers;
    }

    private static String mr(String action, String sha) {
        return """
                {
                  "object_kind": "merge_request",
                  "user": { "name": "Ada", "username": "ada" },
                  "object_attributes": {
                    "iid": 42, "action": "%s", "state": "opened", "draft": false,
                    "title": "t", "url": "https://gitlab/mr/42",
                    "source_branch": "fix", "target_branch": "main",
                    "last_commit": { "id": "%s", "message": "m",
                      "author": { "name": "Ada", "email": "a@x" } }
                  }
                }
                """.formatted(action, sha);
    }

    private static String note(boolean system) {
        return note(system, "ada");
    }

    private static String note(boolean system, String username) {
        return """
                {
                  "object_kind": "note",
                  "user": { "username": "%s" },
                  "object_attributes": {
                    "noteable_type": "MergeRequest",
                    "system": %s,
                    "discussion_id": "disc-1",
                    "id": 9001,
                    "note": "why?"
                  },
                  "merge_request": { "iid": 42 }
                }
                """.formatted(username, system);
    }
}
